package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Cancelamento;
import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.Pagamento;
import br.com.academiagestao.cobranca.dominio.StatusCobranca;
import br.com.academiagestao.cobranca.dominio.TipoCobranca;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Grava e lê cobranças com SQL: o domínio é Java puro, sem anotações de JPA (ADR 0024). A
 * versão protege contra duas alterações da mesma cobrança ao mesmo tempo.
 */
@Repository
class CobrancaRepository {

    private static final String COLUNAS = """
            id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status, pago_acrescimo, pago_juros,
            pago_total, meio_de_pagamento, paga_em, paga_por, cancelada_em, cancelada_por, motivo_cancelamento, estornada_no_dia,
            versao""";

    private final JdbcTemplate jdbc;

    CobrancaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** As cobranças da matrícula, travadas até o fim da transação: um pagamento ao mesmo tempo espera. */
    List<Cobranca> travarDaMatricula(UUID matriculaId) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cobrancas WHERE matricula_id = ? ORDER BY tipo, parcela FOR UPDATE",
                this::mapear, matriculaId);
    }

    /** Trava a cobrança até o fim da transação: dois pagamentos ao mesmo tempo passam um de cada vez. */
    Optional<Cobranca> travar(UUID id) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cobrancas WHERE id = ? FOR UPDATE", this::mapear, id)
                .stream().findFirst();
    }

    Optional<Cobranca> buscar(UUID id) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cobrancas WHERE id = ?", this::mapear, id).stream().findFirst();
    }

    /** As do aluno, da que vence primeiro para a última (inclusive as pagas e canceladas). */
    List<Cobranca> doAluno(UUID alunoId) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cobrancas WHERE aluno_id = ? ORDER BY vencimento, tipo, parcela",
                this::mapear, alunoId);
    }

    /**
     * Alguma em aberto com o prazo antes do dia limite? (o "pode treinar?"). O prazo é o
     * vencimento ou o dia do estorno, o que vier depois (coluna gerada, V25).
     */
    boolean temEmAbertoVencidaAntesDe(UUID alunoId, LocalDate limite) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cobrancas WHERE aluno_id = ? "
                + "AND status = 'PENDENTE' AND prazo < ?)", Boolean.class, alunoId, limite));
    }

    /** As em aberto com o prazo antes de hoje, da que atrasou primeiro para a última. */
    List<Cobranca> atrasadas(LocalDate hoje, int pagina, int tamanho) {
        return jdbc.query("SELECT " + COLUNAS + " FROM cobrancas WHERE status = 'PENDENTE' AND prazo < ? "
                + "ORDER BY prazo, id LIMIT ? OFFSET ?", this::mapear, hoje, tamanho, (long) pagina * tamanho);
    }

    long contarAtrasadas(LocalDate hoje) {
        return jdbc.queryForObject("SELECT count(*) FROM cobrancas WHERE status = 'PENDENTE' AND prazo < ?",
                Long.class, hoje);
    }

    void inserir(Cobranca cobranca) {
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                cobranca.getId(), cobranca.getMatriculaId(), cobranca.getAlunoId(), cobranca.getTipo().name(),
                cobranca.getParcela(), cobranca.getValor(), cobranca.getVencimento(), cobranca.getStatus().name());
    }

    /** Grava o estado atual; se outra transação mudou a cobrança antes, falha (versão). */
    void atualizar(Cobranca cobranca) {
        Pagamento pagamento = cobranca.getPagamento();
        Cancelamento cancelamento = cobranca.getCancelamento();
        int linhas = jdbc.update("""
                UPDATE cobrancas
                SET vencimento = ?, status = ?, pago_acrescimo = ?, pago_juros = ?, pago_total = ?, meio_de_pagamento = ?,
                    paga_em = ?, paga_por = ?, cancelada_em = ?, cancelada_por = ?, motivo_cancelamento = ?,
                    estornada_no_dia = ?, versao = versao + 1
                WHERE id = ? AND versao = ?""",
                cobranca.getVencimento(), cobranca.getStatus().name(),
                pagamento == null ? null : pagamento.acrescimo(), pagamento == null ? null : pagamento.juros(),
                pagamento == null ? null : pagamento.total(), pagamento == null ? null : pagamento.meio().name(),
                pagamento == null ? null : Timestamp.from(pagamento.em()), pagamento == null ? null : pagamento.por(),
                cancelamento == null ? null : Timestamp.from(cancelamento.em()),
                cancelamento == null ? null : cancelamento.por(), cancelamento == null ? null : cancelamento.motivo(),
                cobranca.getEstornadaNoDia(), cobranca.getId(), cobranca.getVersao());
        if (linhas != 1) {
            throw new ObjectOptimisticLockingFailureException(Cobranca.class, cobranca.getId());
        }
    }

    /** Desde quando a matrícula tem cobranças no sistema; vazio = ainda não foi vista. */
    Optional<LocalDate> aPartirDe(UUID matriculaId) {
        return jdbc.query("SELECT a_partir_de FROM cronogramas WHERE matricula_id = ?",
                (rs, i) -> rs.getObject(1, LocalDate.class), matriculaId).stream().findFirst();
    }

    void registrarCronograma(UUID matriculaId, LocalDate aPartirDe) {
        jdbc.update("INSERT INTO cronogramas (matricula_id, a_partir_de) VALUES (?, ?)", matriculaId, aPartirDe);
    }

    /** Das matrículas informadas, as que ainda não têm cronograma (as de antes da cobrança). */
    Set<UUID> semCronograma(Collection<UUID> matriculaIds) {
        if (matriculaIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT m FROM unnest(?::uuid[]) AS m
                WHERE NOT EXISTS (SELECT 1 FROM cronogramas c WHERE c.matricula_id = m)""",
                UUID.class, (Object) matriculaIds.toArray(UUID[]::new)));
    }

    private Cobranca mapear(ResultSet rs, int linha) throws SQLException {
        Pagamento pagamento = rs.getTimestamp("paga_em") == null ? null : new Pagamento(
                rs.getBigDecimal("pago_acrescimo"), rs.getBigDecimal("pago_juros"), rs.getBigDecimal("pago_total"),
                MeioDePagamento.valueOf(rs.getString("meio_de_pagamento")), rs.getTimestamp("paga_em").toInstant(),
                rs.getObject("paga_por", UUID.class));
        Cancelamento cancelamento = rs.getTimestamp("cancelada_em") == null ? null : new Cancelamento(
                rs.getTimestamp("cancelada_em").toInstant(), rs.getObject("cancelada_por", UUID.class),
                rs.getString("motivo_cancelamento"));
        return Cobranca.restaurar(rs.getObject("id", UUID.class), rs.getObject("matricula_id", UUID.class),
                rs.getObject("aluno_id", UUID.class), TipoCobranca.valueOf(rs.getString("tipo")), rs.getInt("parcela"),
                rs.getBigDecimal("valor"), rs.getObject("vencimento", LocalDate.class),
                StatusCobranca.valueOf(rs.getString("status")), pagamento, cancelamento,
                rs.getObject("estornada_no_dia", LocalDate.class), rs.getLong("versao"));
    }
}
