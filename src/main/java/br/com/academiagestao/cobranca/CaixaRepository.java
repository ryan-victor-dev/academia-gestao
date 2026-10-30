package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Caixa;
import br.com.academiagestao.cobranca.dominio.Caixa.Fechamento;
import br.com.academiagestao.cobranca.dominio.Caixa.Reabertura;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * O caixa do dia, os fechamentos e as reaberturas (V35, ADR 0031). Fechamentos e reaberturas
 * só se acrescentam; do caixa só muda a situação.
 *
 * As travas contra a corrida entre fechar e receber ficam na linha do caixa: quem recebe a lê
 * com {@code FOR SHARE} (vários pagamentos ao mesmo tempo não se esperam) e quem fecha ou
 * reabre, com {@code FOR UPDATE}. O fechamento espera os pagamentos em andamento e só então
 * soma o dinheiro; um pagamento que chega depois espera o fechamento e o encontra fechado.
 */
@Repository
class CaixaRepository {

    private static final String COLUNAS = "id, dia, troco, troco_esperado, situacao, aberto_em, aberto_por";

    private final JdbcTemplate jdbc;

    CaixaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Caixa> doDia(LocalDate dia) {
        return jdbc.query("SELECT " + COLUNAS + " FROM caixas WHERE dia = ?", this::mapear, dia).stream().findFirst();
    }

    /** Para receber ou estornar: espera um fechamento em andamento, sem bloquear outros pagamentos. */
    Optional<Caixa> travarParaReceber(LocalDate dia) {
        return jdbc.query("SELECT " + COLUNAS + " FROM caixas WHERE dia = ? FOR SHARE", this::mapear, dia)
                .stream().findFirst();
    }

    /** Para fechar ou reabrir: espera os pagamentos em andamento terminarem. */
    Optional<Caixa> travar(LocalDate dia) {
        return jdbc.query("SELECT " + COLUNAS + " FROM caixas WHERE dia = ? FOR UPDATE", this::mapear, dia)
                .stream().findFirst();
    }

    /** O dia mais antigo antes de {@code dia} que ficou aberto (esquecido ou reaberto). */
    Optional<LocalDate> anteriorAberto(LocalDate dia) {
        return jdbc.query("SELECT dia FROM caixas WHERE situacao = 'ABERTO' AND dia < ? ORDER BY dia LIMIT 1",
                (rs, linha) -> rs.getObject("dia", LocalDate.class), dia).stream().findFirst();
    }

    /**
     * O que ficou na gaveta (contado − retirado) no último fechamento do caixa mais recente antes
     * de {@code dia}; vazio se não há caixa antes. Ao abrir, os anteriores já estão fechados.
     */
    Optional<BigDecimal> trocoQueFicou(LocalDate dia) {
        return jdbc.query("""
                SELECT f.contado - f.retirado AS ficou FROM fechamentos_de_caixa f JOIN caixas c ON c.id = f.caixa_id
                WHERE c.dia < ? ORDER BY c.dia DESC, f.fechado_em DESC LIMIT 1""",
                (rs, linha) -> rs.getBigDecimal("ficou"), dia).stream().findFirst();
    }

    void inserir(Caixa c) {
        jdbc.update("INSERT INTO caixas (" + COLUNAS + ") VALUES (?, ?, ?, ?, ?, ?, ?)", c.getId(), c.getDia(),
                c.getTroco(), c.getTrocoEsperado(), c.getSituacao().name(), Timestamp.from(c.getAbertoEm()),
                c.getAbertoPor());
    }

    void atualizarSituacao(Caixa c) {
        jdbc.update("UPDATE caixas SET situacao = ? WHERE id = ?", c.getSituacao().name(), c.getId());
    }

    void inserir(Fechamento f) {
        jdbc.update("""
                INSERT INTO fechamentos_de_caixa (id, caixa_id, contado, retirado, esperado, diferenca, justificativa,
                                                  fechado_em, fechado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", f.id(), f.caixaId(), f.contado(), f.retirado(), f.esperado(),
                f.diferenca(), f.justificativa(), Timestamp.from(f.em()), f.por());
    }

    void inserir(Reabertura r) {
        jdbc.update("INSERT INTO reaberturas_de_caixa (id, caixa_id, motivo, reaberto_em, reaberto_por) "
                + "VALUES (?, ?, ?, ?, ?)", r.id(), r.caixaId(), r.motivo(), Timestamp.from(r.em()), r.por());
    }

    /** Os caixas do período, por dia. */
    List<Caixa> doPeriodo(LocalDate de, LocalDate ate) {
        return jdbc.query("SELECT " + COLUNAS + " FROM caixas WHERE dia BETWEEN ? AND ? ORDER BY dia", this::mapear,
                de, ate);
    }

    /** Os dias ainda abertos (o de hoje, um esquecido ou um reaberto), do mais antigo ao mais novo. */
    List<Caixa> abertos() {
        return jdbc.query("SELECT " + COLUNAS + " FROM caixas WHERE situacao = 'ABERTO' ORDER BY dia", this::mapear);
    }

    /** Os fechamentos do caixa, do primeiro ao último. */
    List<Fechamento> fechamentos(UUID caixaId) {
        return jdbc.query(FECHAMENTOS + "WHERE f.caixa_id = ? ORDER BY f.fechado_em", this::fechamento, caixaId);
    }

    /** Os fechamentos dos caixas do período, do primeiro ao último. */
    List<Fechamento> fechamentosDoPeriodo(LocalDate de, LocalDate ate) {
        return jdbc.query(FECHAMENTOS + "JOIN caixas c ON c.id = f.caixa_id WHERE c.dia BETWEEN ? AND ? "
                + "ORDER BY f.fechado_em", this::fechamento, de, ate);
    }

    /** As reaberturas do caixa, da primeira à última. */
    List<Reabertura> reaberturas(UUID caixaId) {
        return jdbc.query(REABERTURAS + "WHERE r.caixa_id = ? ORDER BY r.reaberto_em", this::reabertura, caixaId);
    }

    /** As reaberturas dos caixas do período, da primeira à última. */
    List<Reabertura> reaberturasDoPeriodo(LocalDate de, LocalDate ate) {
        return jdbc.query(REABERTURAS + "JOIN caixas c ON c.id = r.caixa_id WHERE c.dia BETWEEN ? AND ? "
                + "ORDER BY r.reaberto_em", this::reabertura, de, ate);
    }

    private static final String FECHAMENTOS = """
            SELECT f.id, f.caixa_id, f.contado, f.retirado, f.esperado, f.diferenca, f.justificativa, f.fechado_em,
                   f.fechado_por
            FROM fechamentos_de_caixa f\s""";

    private static final String REABERTURAS = """
            SELECT r.id, r.caixa_id, r.motivo, r.reaberto_em, r.reaberto_por FROM reaberturas_de_caixa r\s""";

    private Fechamento fechamento(ResultSet rs, int linha) throws SQLException {
        return new Fechamento(rs.getObject("id", UUID.class), rs.getObject("caixa_id", UUID.class),
                rs.getBigDecimal("contado"), rs.getBigDecimal("retirado"), rs.getBigDecimal("esperado"),
                rs.getBigDecimal("diferenca"),
                rs.getString("justificativa"), rs.getTimestamp("fechado_em").toInstant(),
                rs.getObject("fechado_por", UUID.class));
    }

    private Reabertura reabertura(ResultSet rs, int linha) throws SQLException {
        return new Reabertura(rs.getObject("id", UUID.class), rs.getObject("caixa_id", UUID.class),
                rs.getString("motivo"), rs.getTimestamp("reaberto_em").toInstant(),
                rs.getObject("reaberto_por", UUID.class));
    }

    private Caixa mapear(ResultSet rs, int linha) throws SQLException {
        return Caixa.restaurar(rs.getObject("id", UUID.class), rs.getObject("dia", LocalDate.class),
                rs.getBigDecimal("troco"), rs.getBigDecimal("troco_esperado"),
                Caixa.Situacao.valueOf(rs.getString("situacao")),
                rs.getTimestamp("aberto_em").toInstant(), rs.getObject("aberto_por", UUID.class));
    }
}
