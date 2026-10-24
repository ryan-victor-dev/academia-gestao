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
        /** O dinheiro chegou, mas não pagou nada: a cobrança já não estava em aberto, ou veio outro valor. */
        A_DEVOLVER,
        DEVOLVIDO;

        /** Ainda pode chegar dinheiro: o aluno pode ter pago no último segundo. */
        boolean recebe() {
            return this == AGUARDANDO || this == EXPIRADO || this == CANCELADO;
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
    private final Instant validoAte;
    private final Instant geradoEm;
    private final UUID geradoPor;
    private Situacao situacao;
    private BigDecimal recebido;
    private Instant recebidoEm;
    private String motivoDaDevolucao;
    private Instant devolvidoEm;
    private UUID devolvidoPor;

    private PagamentoOnline(UUID id, UUID cobrancaId, UUID alunoId, Forma forma, String provedor, String idExterno,
                            BigDecimal valor, BigDecimal acrescimo, BigDecimal juros, String pixCopiaECola,
                            String boletoLinhaDigitavel, String boletoUrl, Instant validoAte, Instant geradoEm,
                            UUID geradoPor, Situacao situacao, BigDecimal recebido, Instant recebidoEm,
                            String motivoDaDevolucao, Instant devolvidoEm, UUID devolvidoPor) {
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
        this.validoAte = validoAte;
        this.geradoEm = geradoEm;
        this.geradoPor = geradoPor;
        this.situacao = situacao;
        this.recebido = recebido;
        this.recebidoEm = recebidoEm;
        this.motivoDaDevolucao = motivoDaDevolucao;
        this.devolvidoEm = devolvidoEm;
        this.devolvidoPor = devolvidoPor;
    }

    /** O que a empresa gerou, aguardando o pagamento. */
    static PagamentoOnline novo(UUID id, UUID cobrancaId, UUID alunoId, Forma forma, String provedor,
                                ProvedorDePagamento.Gerado gerado, BigDecimal valor, BigDecimal acrescimo,
                                BigDecimal juros, Instant validoAte, UUID geradoPor, Instant agora) {
        Objects.requireNonNull(gerado.idExterno(), "a empresa não devolveu o id do pagamento");
        if (forma == Forma.PIX ? gerado.pixCopiaECola() == null : gerado.boletoLinhaDigitavel() == null) {
            throw new IllegalStateException("a empresa não devolveu o " + (forma == Forma.PIX ? "Pix" : "boleto"));
        }
        return new PagamentoOnline(id, cobrancaId, alunoId, forma, provedor, gerado.idExterno(), valor, acrescimo,
                juros, gerado.pixCopiaECola(), gerado.boletoLinhaDigitavel(), gerado.boletoUrl(), validoAte,
                agora.truncatedTo(ChronoUnit.MICROS), Objects.requireNonNull(geradoPor), Situacao.AGUARDANDO,
                null, null, null, null, null);
    }

    static PagamentoOnline restaurar(UUID id, UUID cobrancaId, UUID alunoId, Forma forma, String provedor,
                                     String idExterno, BigDecimal valor, BigDecimal acrescimo, BigDecimal juros,
                                     String pixCopiaECola, String boletoLinhaDigitavel, String boletoUrl,
                                     Instant validoAte, Instant geradoEm, UUID geradoPor, Situacao situacao,
                                     BigDecimal recebido, Instant recebidoEm, String motivoDaDevolucao,
                                     Instant devolvidoEm, UUID devolvidoPor) {
        return new PagamentoOnline(id, cobrancaId, alunoId, forma, provedor, idExterno, valor, acrescimo, juros,
                pixCopiaECola, boletoLinhaDigitavel, boletoUrl, validoAte, geradoEm, geradoPor, situacao, recebido,
                recebidoEm, motivoDaDevolucao, devolvidoEm, devolvidoPor);
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

    /** O dinheiro chegou, mas não pagou nada: fica para o ADMIN devolver. */
    void aDevolver(BigDecimal valorRecebido, Instant em, String motivo) {
        conferir(Situacao.A_DEVOLVER, situacao.recebe());
        this.situacao = Situacao.A_DEVOLVER;
        this.recebido = Objects.requireNonNull(valorRecebido);
        this.recebidoEm = em.truncatedTo(ChronoUnit.MICROS);
        this.motivoDaDevolucao = Objects.requireNonNull(motivo);
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

    /** O ADMIN devolveu o dinheiro pelo painel da empresa. */
    void devolver(UUID por, Instant agora) {
        conferir(Situacao.DEVOLVIDO, situacao == Situacao.A_DEVOLVER);
        this.situacao = Situacao.DEVOLVIDO;
        this.devolvidoPor = Objects.requireNonNull(por);
        this.devolvidoEm = agora.truncatedTo(ChronoUnit.MICROS);
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
    Instant getValidoAte() { return validoAte; }
    Instant getGeradoEm() { return geradoEm; }
    UUID getGeradoPor() { return geradoPor; }
    Situacao getSituacao() { return situacao; }
    BigDecimal getRecebido() { return recebido; }
    Instant getRecebidoEm() { return recebidoEm; }
    String getMotivoDaDevolucao() { return motivoDaDevolucao; }
    Instant getDevolvidoEm() { return devolvidoEm; }
    UUID getDevolvidoPor() { return devolvidoPor; }
}
