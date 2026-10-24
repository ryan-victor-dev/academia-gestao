package br.com.academiagestao.pagamentos;

import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Um Pix ou boleto gerado para uma cobrança (ADR 0034), com o valor combinado no dia, em partes.
 * O estado só muda por métodos com nome de negócio, que conferem a transição.
 */
class PagamentoOnline {

    static final int TAMANHO_MAXIMO_MOTIVO = 500;

    enum Situacao {
        AGUARDANDO,
        /** Pago, e a cobrança ficou paga. */
        PAGO,
        EXPIRADO,
        /** Trocado por um novo (o valor do dia mudou) ou cancelado na empresa. */
        CANCELADO,
        /**
         * Há dinheiro para devolver: tudo (a cobrança já não estava em aberto, ou veio menos que o
         * combinado), ou só o que passou do combinado, e a cobrança ficou paga (ADR 0035).
         */
        A_DEVOLVER,
        /**
         * A devolução pela empresa em andamento (ADR 0036): enquanto isso, não se marca "devolvido
         * por fora". Parado aqui (o sistema caiu no meio), pode ser repetido com a mesma chave.
         */
        DEVOLVENDO,
        DEVOLVIDO,
        /**
         * O pagador contestou no banco, que devolveu o dinheiro a ele (ADR 0037). Vem de um
         * recebido; se ele tinha pago a cobrança, ela volta em aberto.
         */
        CONTESTADO;

        /** Ainda pode chegar dinheiro: o aluno pode ter pago no último segundo. */
        boolean recebe() {
            return this == AGUARDANDO || this == EXPIRADO || this == CANCELADO;
        }

        /** O dinheiro chegou: ainda pode ser contestado. */
        boolean contestavel() {
            return this == PAGO || this == A_DEVOLVER || this == DEVOLVENDO || this == DEVOLVIDO;
        }
    }

    private final UUID id;
    private final UUID cobrancaId;
    private final UUID alunoId;
    private final Forma forma;
    private final String provedor;
    private final String idExterno;
    private final BigDecimal valor;
    private final BigDecimal acrescimo;
    private final BigDecimal juros;
    private final String pixCopiaECola;
    private final String boletoLinhaDigitavel;
    private final String boletoUrl;
    private final String linkDePagamento;
    private final Instant validoAte;
    private final Instant geradoEm;
    private final UUID geradoPor;
    private Situacao situacao;
    private BigDecimal recebido;
    private Instant recebidoEm;
    private String motivoDaDevolucao;
    private BigDecimal valorADevolver;
    private Instant devolvidoEm;
    private UUID devolvidoPor;
    private Instant contestadoEm;
    private Boolean cobrancaReaberta;

    private PagamentoOnline(UUID id, UUID cobrancaId, UUID alunoId, Forma forma, String provedor, String idExterno,
                            BigDecimal valor, BigDecimal acrescimo, BigDecimal juros, String pixCopiaECola,
                            String boletoLinhaDigitavel, String boletoUrl, String linkDePagamento, Instant validoAte,
                            Instant geradoEm, UUID geradoPor, Situacao situacao, BigDecimal recebido, Instant recebidoEm,
                            String motivoDaDevolucao, BigDecimal valorADevolver, Instant devolvidoEm,
                            UUID devolvidoPor, Instant contestadoEm, Boolean cobrancaReaberta) {
        this.id = id;
        this.cobrancaId = cobrancaId;
        this.alunoId = alunoId;
        this.forma = forma;
        this.provedor = provedor;
        this.idExterno = idExterno;
        this.valor = valor;
        this.acrescimo = acrescimo;
        this.juros = juros;
        this.pixCopiaECola = pixCopiaECola;
        this.boletoLinhaDigitavel = boletoLinhaDigitavel;
        this.boletoUrl = boletoUrl;
        this.linkDePagamento = linkDePagamento;
        this.validoAte = validoAte;
        this.geradoEm = geradoEm;
        this.geradoPor = geradoPor;
        this.situacao = situacao;
        this.recebido = recebido;
        this.recebidoEm = recebidoEm;
        this.motivoDaDevolucao = motivoDaDevolucao;
        this.valorADevolver = valorADevolver;
        this.devolvidoEm = devolvidoEm;
        this.devolvidoPor = devolvidoPor;
        this.contestadoEm = contestadoEm;
        this.cobrancaReaberta = cobrancaReaberta;
    }

    /** O que a empresa gerou, aguardando o pagamento. */
    static PagamentoOnline novo(UUID id, UUID cobrancaId, UUID alunoId, Forma forma, String provedor,
                                ProvedorDePagamento.Gerado gerado, BigDecimal valor, BigDecimal acrescimo,
                                BigDecimal juros, Instant validoAte, UUID geradoPor, Instant agora) {
        Objects.requireNonNull(gerado.idExterno(), "a empresa não devolveu o id do pagamento");
        String obrigatorio = switch (forma) {
            case PIX -> gerado.pixCopiaECola();
            case BOLETO -> gerado.boletoLinhaDigitavel();
            case CREDITO, DEBITO -> gerado.linkDePagamento();
        };
        if (obrigatorio == null) {
            throw new IllegalStateException("a empresa não devolveu " + forma.nome());
        }
        return new PagamentoOnline(id, cobrancaId, alunoId, forma, provedor, gerado.idExterno(), valor, acrescimo,
                juros, gerado.pixCopiaECola(), gerado.boletoLinhaDigitavel(), gerado.boletoUrl(),
                gerado.linkDePagamento(), validoAte,
                agora.truncatedTo(ChronoUnit.MICROS), Objects.requireNonNull(geradoPor), Situacao.AGUARDANDO,
                null, null, null, null, null, null, null, null);
    }

    static PagamentoOnline restaurar(UUID id, UUID cobrancaId, UUID alunoId, Forma forma, String provedor,
                                     String idExterno, BigDecimal valor, BigDecimal acrescimo, BigDecimal juros,
                                     String pixCopiaECola, String boletoLinhaDigitavel, String boletoUrl,
                                     String linkDePagamento, Instant validoAte, Instant geradoEm, UUID geradoPor, Situacao situacao,
                                     BigDecimal recebido, Instant recebidoEm, String motivoDaDevolucao,
                                     BigDecimal valorADevolver, Instant devolvidoEm, UUID devolvidoPor,
                                     Instant contestadoEm, Boolean cobrancaReaberta) {
        return new PagamentoOnline(id, cobrancaId, alunoId, forma, provedor, idExterno, valor, acrescimo, juros,
                pixCopiaECola, boletoLinhaDigitavel, boletoUrl, linkDePagamento, validoAte, geradoEm, geradoPor,
                situacao, recebido,
                recebidoEm, motivoDaDevolucao, valorADevolver, devolvidoEm, devolvidoPor, contestadoEm,
                cobrancaReaberta);
    }

    BigDecimal total() {
        return valor.add(acrescimo).add(juros);
    }

    /** Ainda aguardando e dentro do prazo: pedir de novo devolve este. */
    boolean vale(Instant agora) {
        return situacao == Situacao.AGUARDANDO && agora.isBefore(validoAte);
    }

    /** A cobrança ficou paga com este dinheiro. */
    void receber(BigDecimal valorRecebido, Instant em) {
        conferir(Situacao.PAGO, situacao.recebe());
        this.situacao = Situacao.PAGO;
        this.recebido = Objects.requireNonNull(valorRecebido);
        this.recebidoEm = em.truncatedTo(ChronoUnit.MICROS);
    }

    /** O dinheiro chegou, mas não pagou nada: fica todo para o ADMIN devolver. */
    void aDevolver(BigDecimal valorRecebido, Instant em, String motivo) {
        aDevolver(valorRecebido, valorRecebido, em, motivo);
    }

    /** Pago a mais (ADR 0035): a cobrança ficou paga com o combinado, e só a sobra volta. */
    void pagoComSobra(BigDecimal valorRecebido, Instant em, String motivo) {
        BigDecimal sobra = valorRecebido.subtract(total());
        if (sobra.signum() <= 0) {
            throw new IllegalArgumentException("sem sobra: recebido " + valorRecebido + ", combinado " + total());
        }
        aDevolver(valorRecebido, sobra, em, motivo);
    }

    private void aDevolver(BigDecimal valorRecebido, BigDecimal devolver, Instant em, String motivo) {
        conferir(Situacao.A_DEVOLVER, situacao.recebe());
        this.situacao = Situacao.A_DEVOLVER;
        this.recebido = Objects.requireNonNull(valorRecebido);
        this.recebidoEm = em.truncatedTo(ChronoUnit.MICROS);
        this.motivoDaDevolucao = Objects.requireNonNull(motivo);
        this.valorADevolver = devolver;
    }

    /** Parte do dinheiro pagou a cobrança (o pago a mais); o resto volta. */
    boolean pagouACobranca() {
        return valorADevolver != null && valorADevolver.compareTo(recebido) < 0;
    }

    /** Passou do prazo sem pagamento (a empresa encerrou, ou a tolerância acabou). */
    void expirar() {
        conferir(Situacao.EXPIRADO, situacao == Situacao.AGUARDANDO);
        this.situacao = Situacao.EXPIRADO;
    }

    /** Trocado por um novo. */
    void cancelar() {
        conferir(Situacao.CANCELADO, situacao == Situacao.AGUARDANDO);
        this.situacao = Situacao.CANCELADO;
    }

    /** Antes de pedir a devolução à empresa: daqui em diante, "por fora" é recusado. */
    void iniciarDevolucao() {
        conferir(Situacao.DEVOLVENDO, situacao == Situacao.A_DEVOLVER);
        this.situacao = Situacao.DEVOLVENDO;
    }

    /** A empresa recusou ou não respondeu: volta a ficar a devolver (de novo, ou por fora). */
    void desistirDaDevolucao() {
        conferir(Situacao.A_DEVOLVER, situacao == Situacao.DEVOLVENDO);
        this.situacao = Situacao.A_DEVOLVER;
    }

    /**
     * O dinheiro voltou ao aluno: pela empresa, a pedido do ADMIN (de DEVOLVENDO, ou de A_DEVOLVER
     * se outro pedido já desistiu), ou por fora, e o ADMIN marcou (só de A_DEVOLVER: quem chama confere).
     */
    void devolver(UUID por, Instant agora) {
        conferir(Situacao.DEVOLVIDO, situacao == Situacao.A_DEVOLVER || situacao == Situacao.DEVOLVENDO);
        this.situacao = Situacao.DEVOLVIDO;
        this.devolvidoPor = Objects.requireNonNull(por);
        this.devolvidoEm = agora.truncatedTo(ChronoUnit.MICROS);
    }

    /** Se o dinheiro deste pagamento pagou a cobrança: pago, ou pago a mais (a sobra a devolver). */
    boolean pagouACobrancaAntesDaContestacao() {
        return situacao == Situacao.PAGO || pagouACobranca();
    }

    /** O pagador contestou no banco (ADR 0037); {@code reabriu}: a cobrança voltou em aberto. */
    void contestar(Instant agora, boolean reabriu) {
        conferir(Situacao.CONTESTADO, situacao.contestavel());
        this.situacao = Situacao.CONTESTADO;
        this.contestadoEm = agora.truncatedTo(ChronoUnit.MICROS);
        this.cobrancaReaberta = reabriu;
    }

    private void conferir(Situacao destino, boolean pode) {
        if (!pode) {
            throw new IllegalStateException("pagamento online " + situacao + " não vai para " + destino);
        }
    }

    UUID getId() { return id; }
    UUID getCobrancaId() { return cobrancaId; }
    UUID getAlunoId() { return alunoId; }
    Forma getForma() { return forma; }
    String getProvedor() { return provedor; }
    String getIdExterno() { return idExterno; }
    BigDecimal getValor() { return valor; }
    BigDecimal getAcrescimo() { return acrescimo; }
    BigDecimal getJuros() { return juros; }
    String getPixCopiaECola() { return pixCopiaECola; }
    String getBoletoLinhaDigitavel() { return boletoLinhaDigitavel; }
    String getBoletoUrl() { return boletoUrl; }
    String getLinkDePagamento() { return linkDePagamento; }
    Instant getValidoAte() { return validoAte; }
    Instant getGeradoEm() { return geradoEm; }
    UUID getGeradoPor() { return geradoPor; }
    Situacao getSituacao() { return situacao; }
    BigDecimal getRecebido() { return recebido; }
    Instant getRecebidoEm() { return recebidoEm; }
    String getMotivoDaDevolucao() { return motivoDaDevolucao; }
    BigDecimal getValorADevolver() { return valorADevolver; }
    Instant getDevolvidoEm() { return devolvidoEm; }
    UUID getDevolvidoPor() { return devolvidoPor; }
    Instant getContestadoEm() { return contestadoEm; }
    Boolean getCobrancaReaberta() { return cobrancaReaberta; }
}
