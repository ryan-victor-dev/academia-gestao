package br.com.academiagestao.cobranca.dominio;

/**
 * Como o aluno pagou: no balcão (ADR 0024) ou pelo app, por uma empresa de pagamentos (ADRs 0034
 * e 0037). O online não passa pelo caixa: o dinheiro cai na conta, não na gaveta.
 */
public enum MeioDePagamento {
    DINHEIRO,
    PIX,
    CARTAO_DEBITO,
    CARTAO_CREDITO,
    PIX_ONLINE,
    BOLETO,
    CREDITO_ONLINE,
    DEBITO_ONLINE;

    public boolean online() {
        return this == PIX_ONLINE || this == BOLETO || this == CREDITO_ONLINE || this == DEBITO_ONLINE;
    }

    /**
     * Pix e cartão do balcão levam o código da transação (ADR 0046, item 10): o ADMIN confere com o
     * extrato e com a maquininha. Dinheiro se confere na contagem; o online, pela empresa.
     */
    public boolean exigeCodigo() {
        return this == PIX || this == CARTAO_DEBITO || this == CARTAO_CREDITO;
    }
}
