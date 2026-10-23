package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Lancamento;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.MovimentoDoDia;
import br.com.academiagestao.compartilhado.Calendario;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** O registro de pagamentos e estornos (V24, ADR 0025): só INSERT e SELECT, como a trilha. */
@Repository
class LancamentoRepository {

    private static final String COLUNAS = """
            id, cobranca_id, tipo, estorna, valor, acrescimo, juros, total, meio_de_pagamento, registrado_em,
            registrado_por, motivo, caixa_id""";

    private final JdbcTemplate jdbc;

    LancamentoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void inserir(Lancamento l) {
        jdbc.update("INSERT INTO lancamentos (" + COLUNAS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                l.id(), l.cobrancaId(), l.tipo().name(), l.estorna(), l.valor(), l.acrescimo(), l.juros(), l.total(),
                l.meio().name(), Timestamp.from(l.em()), l.por(), l.motivo(), l.caixaId());
    }

    /** Os da cobrança, na ordem em que aconteceram. */
    List<Lancamento> daCobranca(UUID cobrancaId) {
        return jdbc.query("SELECT " + COLUNAS + " FROM lancamentos WHERE cobranca_id = ? ORDER BY registrado_em, tipo DESC",
                this::mapear, cobrancaId);
    }

    /** O pagamento da cobrança que ainda não foi estornado (há no máximo um: a cobrança paga uma vez por vez). */
    Optional<Lancamento> pagamentoEmVigor(UUID cobrancaId) {
        return jdbc.query("SELECT " + COLUNAS + " FROM lancamentos p WHERE cobranca_id = ? AND tipo = 'PAGAMENTO' "
                + "AND NOT EXISTS (SELECT 1 FROM lancamentos e WHERE e.estorna = p.id)", this::mapear, cobrancaId)
                .stream().findFirst();
    }

    /** O que entrou e saiu no caixa, por forma: pagamentos e estornos registrados nele. */
    MovimentoDoDia movimentoDoCaixa(UUID caixaId) {
        MovimentoDoDia[] movimento = {MovimentoDoDia.VAZIO};
        jdbc.query("""
                SELECT meio_de_pagamento, tipo, sum(total) AS soma, count(*) AS quantos
                FROM lancamentos WHERE caixa_id = ? GROUP BY meio_de_pagamento, tipo""",
                rs -> {
                    movimento[0] = movimento[0].com(MeioDePagamento.valueOf(rs.getString("meio_de_pagamento")),
                            Lancamento.Tipo.valueOf(rs.getString("tipo")), rs.getBigDecimal("soma"), rs.getInt("quantos"));
                }, caixaId);
        return movimento[0];
    }

    /**
     * O movimento de cada dia do período (só os dias com algum lançamento). Com caixa, o dia
     * é o do caixa; os lançamentos de antes do caixa (V35), sem ele, entram pelo dia do
     * registro no fuso da academia.
     */
    Map<LocalDate, MovimentoDoDia> movimentoPorDia(LocalDate de, LocalDate ate) {
        Map<LocalDate, MovimentoDoDia> dias = new TreeMap<>();
        jdbc.query("""
                SELECT c.dia, l.meio_de_pagamento, l.tipo, sum(l.total) AS soma, count(*) AS quantos
                FROM caixas c JOIN lancamentos l ON l.caixa_id = c.id
                WHERE c.dia BETWEEN ? AND ?
                GROUP BY c.dia, l.meio_de_pagamento, l.tipo
                UNION ALL
                SELECT (registrado_em AT TIME ZONE ?)::date, meio_de_pagamento, tipo, sum(total), count(*)
                FROM lancamentos
                WHERE caixa_id IS NULL AND registrado_em >= ? AND registrado_em < ?
                GROUP BY 1, meio_de_pagamento, tipo""",
                rs -> {
                    dias.merge(rs.getObject("dia", LocalDate.class), MovimentoDoDia.VAZIO.com(
                            MeioDePagamento.valueOf(rs.getString("meio_de_pagamento")),
                            Lancamento.Tipo.valueOf(rs.getString("tipo")), rs.getBigDecimal("soma"), rs.getInt("quantos")),
                            MovimentoDoDia::mais);
                }, de, ate, Calendario.FUSO.getId(), Timestamp.from(de.atStartOfDay(Calendario.FUSO).toInstant()),
                Timestamp.from(ate.plusDays(1).atStartOfDay(Calendario.FUSO).toInstant()));
        return dias;
    }

    private Lancamento mapear(ResultSet rs, int linha) throws SQLException {
        return new Lancamento(rs.getObject("id", UUID.class), rs.getObject("cobranca_id", UUID.class),
                Lancamento.Tipo.valueOf(rs.getString("tipo")), rs.getObject("estorna", UUID.class),
                rs.getBigDecimal("valor"), rs.getBigDecimal("acrescimo"), rs.getBigDecimal("juros"),
                rs.getBigDecimal("total"), MeioDePagamento.valueOf(rs.getString("meio_de_pagamento")),
                rs.getTimestamp("registrado_em").toInstant(), rs.getObject("registrado_por", UUID.class),
                rs.getString("motivo"), rs.getObject("caixa_id", UUID.class));
    }
}
