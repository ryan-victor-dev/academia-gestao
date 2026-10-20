package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Lancamento;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** O registro de pagamentos e estornos (V24, ADR 0025): só INSERT e SELECT, como a trilha. */
@Repository
class LancamentoRepository {

    private static final String COLUNAS = """
            id, cobranca_id, tipo, estorna, valor, acrescimo, juros, total, meio_de_pagamento, registrado_em,
            registrado_por, motivo""";

    private final JdbcTemplate jdbc;

    LancamentoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void inserir(Lancamento l) {
        jdbc.update("INSERT INTO lancamentos (" + COLUNAS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                l.id(), l.cobrancaId(), l.tipo().name(), l.estorna(), l.valor(), l.acrescimo(), l.juros(), l.total(),
                l.meio().name(), Timestamp.from(l.em()), l.por(), l.motivo());
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

    private Lancamento mapear(ResultSet rs, int linha) throws SQLException {
        return new Lancamento(rs.getObject("id", UUID.class), rs.getObject("cobranca_id", UUID.class),
                Lancamento.Tipo.valueOf(rs.getString("tipo")), rs.getObject("estorna", UUID.class),
                rs.getBigDecimal("valor"), rs.getBigDecimal("acrescimo"), rs.getBigDecimal("juros"),
                rs.getBigDecimal("total"), MeioDePagamento.valueOf(rs.getString("meio_de_pagamento")),
                rs.getTimestamp("registrado_em").toInstant(), rs.getObject("registrado_por", UUID.class),
                rs.getString("motivo"));
    }
}
