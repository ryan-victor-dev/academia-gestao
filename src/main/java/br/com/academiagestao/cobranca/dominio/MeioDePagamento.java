package br.com.academiagestao.cobranca.dominio;

/**
 * Como o aluno pagou: no balcão (ADR 0024) ou pelo app, por uma empresa de pagamentos (ADR 0034).
 * O online não passa pelo caixa: o dinheiro cai na conta, não na gaveta.
 */
public enum MeioDePagamento {
    DINHEIRO,
    PIX,
    CARTAO_DEBITO,
    CARTAO_CREDITO,
    PIX_ONLINE,
    BOLETO;

    public boolean online() {
        return this == PIX_ONLINE || this == BOLETO;
    }
}
