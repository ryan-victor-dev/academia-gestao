package br.com.academiagestao.pagamentos;

import br.com.academiagestao.pagamentos.PagamentoOnline.Situacao;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Os Pix e boletos gerados (V42, ADR 0034). Nunca se apagam: só muda a situação. */
@Repository
class PagamentoOnlineRepository {

    private static final String COLUNAS = """
            id, cobranca_id, aluno_id, forma, provedor, id_externo, valor, acrescimo, juros, pix_copia_e_cola,
            boleto_linha_digitavel, boleto_url, link_de_pagamento, valido_ate, gerado_em, gerado_por, situacao,
            recebido, recebido_em, motivo_da_devolucao, valor_a_devolver, devolvido_em, devolvido_por, contestado_em,
            cobranca_reaberta""";

    private final JdbcTemplate jdbc;

    PagamentoOnlineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void inserir(PagamentoOnline p) {
        jdbc.update("INSERT INTO pagamentos_online (" + COLUNAS + ") VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.getId(), p.getCobrancaId(), p.getAlunoId(), p.getForma().name(), p.getProvedor(), p.getIdExterno(),
                p.getValor(), p.getAcrescimo(), p.getJuros(), p.getPixCopiaECola(), p.getBoletoLinhaDigitavel(),
                p.getBoletoUrl(), p.getLinkDePagamento(), Timestamp.from(p.getValidoAte()), Timestamp.from(p.getGeradoEm()), p.getGeradoPor(),
                p.getSituacao().name(), p.getRecebido(), quando(p.getRecebidoEm()), p.getMotivoDaDevolucao(),
                p.getValorADevolver(), quando(p.getDevolvidoEm()), p.getDevolvidoPor(), quando(p.getContestadoEm()),
                p.getCobrancaReaberta());
    }

    /** Só o que muda depois de gerado. */
    void atualizar(PagamentoOnline p) {
        jdbc.update("""
                UPDATE pagamentos_online SET situacao = ?, recebido = ?, recebido_em = ?, motivo_da_devolucao = ?,
                                             valor_a_devolver = ?, devolvido_em = ?, devolvido_por = ?,
                                             contestado_em = ?, cobranca_reaberta = ?
                WHERE id = ?""", p.getSituacao().name(), p.getRecebido(), quando(p.getRecebidoEm()),
                p.getMotivoDaDevolucao(), p.getValorADevolver(), quando(p.getDevolvidoEm()), p.getDevolvidoPor(),
                quando(p.getContestadoEm()), p.getCobrancaReaberta(), p.getId());
    }

    Optional<PagamentoOnline> buscar(UUID id) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE id = ?", this::mapear, id)
                .stream().findFirst();
    }

    /** Trava a linha até o fim da transação: o aviso e a consulta periódica não registram juntos. */
    Optional<PagamentoOnline> travar(UUID id) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE id = ? FOR UPDATE", this::mapear, id)
                .stream().findFirst();
    }

    Optional<PagamentoOnline> porIdExterno(String provedor, String idExterno) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE provedor = ? AND id_externo = ?",
                this::mapear, provedor, idExterno).stream().findFirst();
    }

    /** Os aguardando da cobrança, do mais novo para o mais antigo. */
    List<PagamentoOnline> aguardandoDaCobranca(UUID cobrancaId) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE cobranca_id = ? "
                + "AND situacao = 'AGUARDANDO' ORDER BY gerado_em DESC", this::mapear, cobrancaId);
    }

    /** Os aguardando do aluno, para mostrar o que ele já gerou. */
    List<PagamentoOnline> aguardandoDoAluno(UUID alunoId) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE aluno_id = ? "
                + "AND situacao = 'AGUARDANDO' ORDER BY gerado_em DESC", this::mapear, alunoId);
    }

    /** Os aguardando de um provedor, do que expira primeiro em diante, para a consulta periódica. */
    List<PagamentoOnline> aguardando(String provedor, int limite) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE situacao = 'AGUARDANDO' "
                + "AND provedor = ? ORDER BY valido_ate LIMIT ?", this::mapear, provedor, limite);
    }

    /**
     * Os cancelados e expirados de um provedor cujo prazo acabou depois de {@code desde}, para a
     * consulta dos encerrados (ADR 0036): ainda podem ter sido pagos.
     */
    List<PagamentoOnline> encerradosRecentes(String provedor, Instant desde, int limite) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE situacao IN ('EXPIRADO', 'CANCELADO') "
                + "AND provedor = ? AND valido_ate > ? ORDER BY valido_ate DESC LIMIT ?", this::mapear, provedor,
                Timestamp.from(desde), limite);
    }

    /** Os cartões recebidos desde {@code desde}, para a consulta diária das contestações (ADR 0037). */
    List<PagamentoOnline> cartoesRecebidos(String provedor, Instant desde, int limite) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online "
                + "WHERE situacao IN ('PAGO', 'A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO') AND forma IN ('CREDITO', 'DEBITO') "
                + "AND provedor = ? AND recebido_em > ? ORDER BY recebido_em DESC LIMIT ?", this::mapear, provedor,
                Timestamp.from(desde), limite);
    }

    /** As contestações desde {@code desde}, da mais nova para a mais antiga (a lista do ADMIN). */
    List<PagamentoOnline> contestados(Instant desde) {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE situacao = 'CONTESTADO' "
                + "AND contestado_em > ? ORDER BY contestado_em DESC", this::mapear, Timestamp.from(desde));
    }

    /** O dinheiro a devolver, e o que está sendo devolvido, do mais antigo para o mais novo. */
    List<PagamentoOnline> aDevolver() {
        return jdbc.query("SELECT " + COLUNAS + " FROM pagamentos_online WHERE situacao IN ('A_DEVOLVER', 'DEVOLVENDO') "
                + "ORDER BY recebido_em", this::mapear);
    }

    private static Timestamp quando(Instant instante) {
        return instante == null ? null : Timestamp.from(instante);
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp t = rs.getTimestamp(coluna);
        return t == null ? null : t.toInstant();
    }

    private PagamentoOnline mapear(ResultSet rs, int linha) throws SQLException {
        return PagamentoOnline.restaurar(rs.getObject("id", UUID.class), rs.getObject("cobranca_id", UUID.class),
                rs.getObject("aluno_id", UUID.class), Forma.valueOf(rs.getString("forma")), rs.getString("provedor"),
                rs.getString("id_externo"), rs.getBigDecimal("valor"), rs.getBigDecimal("acrescimo"),
                rs.getBigDecimal("juros"), rs.getString("pix_copia_e_cola"), rs.getString("boleto_linha_digitavel"),
                rs.getString("boleto_url"), rs.getString("link_de_pagamento"), instante(rs, "valido_ate"), instante(rs, "gerado_em"),
                rs.getObject("gerado_por", UUID.class), Situacao.valueOf(rs.getString("situacao")),
                rs.getBigDecimal("recebido"), instante(rs, "recebido_em"), rs.getString("motivo_da_devolucao"),
                rs.getBigDecimal("valor_a_devolver"), instante(rs, "devolvido_em"),
                rs.getObject("devolvido_por", UUID.class), instante(rs, "contestado_em"),
                rs.getObject("cobranca_reaberta", Boolean.class));
    }
}
