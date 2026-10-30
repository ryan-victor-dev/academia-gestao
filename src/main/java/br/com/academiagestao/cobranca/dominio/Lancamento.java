package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Um lançamento no registro de pagamentos (ADR 0025): só se acrescenta, nunca se altera nem
 * se apaga. O estorno não desfaz o pagamento; é um lançamento novo que aponta para ele, com
 * os mesmos valores. Assim o que entrou e o que saiu do caixa continua conferível.
 *
 * @param caixaId o caixa do dia em que o dinheiro entrou ou saiu (ADR 0031): o estorno sai do
 *                caixa do dia em que é feito, não do dia do pagamento. O online (Pix online e
 *                boleto, ADR 0034) não tem caixa: o dinheiro entra e sai pela conta.
 * @param estorna o pagamento estornado (só no estorno)
 * @param codigo  o código da transação do pagamento (Pix e cartão do balcão, ADR 0046); o estorno
 *                repete o do pagamento
 * @param motivo  só no estorno
 */
public record Lancamento(UUID id, UUID cobrancaId, Tipo tipo, UUID estorna, BigDecimal valor, BigDecimal acrescimo,
                         BigDecimal juros, BigDecimal total, MeioDePagamento meio, String codigo, Instant em, UUID por,
                         String motivo, UUID caixaId) {

    public enum Tipo { PAGAMENTO, ESTORNO }

    /** O pagamento que a cobrança acabou de receber, com as partes do devido naquele dia. */
    public static Lancamento doPagamento(Cobranca cobranca, UUID caixaId) {
        // Paga sempre tem o pagamento, e só ela (Cobranca e o CHECK da V22).
        if (cobranca.getStatus() != StatusCobranca.PAGA) {
            throw new IllegalStateException("só cobrança paga tem lançamento de pagamento");
        }
        Pagamento pagamento = cobranca.getPagamento();
        conferirCaixa(pagamento.meio(), caixaId);
        return new Lancamento(UUID.randomUUID(), cobranca.getId(), Tipo.PAGAMENTO, null, cobranca.getValor(),
                pagamento.acrescimo(), pagamento.juros(), pagamento.total(), pagamento.meio(), pagamento.codigo(), pagamento.em(),
                pagamento.por(), null, caixaId);
    }

    /**
     * O estorno deste pagamento: os mesmos valores e a mesma forma, com quem, quando e por quê,
     * no caixa do dia do estorno.
     */
    public Lancamento estornar(String motivo, UUID por, Instant agora, UUID caixaDoEstorno) {
        if (tipo != Tipo.PAGAMENTO) {
            throw new IllegalStateException("só pagamento se estorna");
        }
        String motivoLimpo = Cobranca.motivoValido(motivo);
        Objects.requireNonNull(por, "quem estornou é obrigatório");
        conferirCaixa(meio, caixaDoEstorno);
        return new Lancamento(UUID.randomUUID(), cobrancaId, Tipo.ESTORNO, id, valor, acrescimo, juros, total, meio,
                codigo, agora.truncatedTo(ChronoUnit.MICROS), por, motivoLimpo, caixaDoEstorno);
    }

    /** No balcão, o caixa do dia é obrigatório; no online, não há caixa. */
    private static void conferirCaixa(MeioDePagamento meio, UUID caixaId) {
        if (meio.online() != (caixaId == null)) {
            throw new IllegalStateException(meio.online() ? "pagamento online não entra no caixa"
                    : "o caixa do dia é obrigatório");
        }
    }
}
