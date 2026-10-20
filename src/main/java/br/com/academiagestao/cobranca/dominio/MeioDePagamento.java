package br.com.academiagestao.cobranca.dominio;

/** Como o aluno pagou no balcão (ADR 0024: sem integração com banco por enquanto). */
public enum MeioDePagamento {
    DINHEIRO,
    PIX,
    CARTAO_DEBITO,
    CARTAO_CREDITO
}
