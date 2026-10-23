package br.com.academiagestao.cobranca.dominio;

/**
 * O caixa não permite a operação agora (vira 409 na API): sem caixa aberto, dia já fechado,
 * já aberto, ou um dia anterior esquecido aberto (ADR 0031).
 */
public class OperacaoDeCaixaRecusadaException extends RuntimeException {

    public OperacaoDeCaixaRecusadaException(String mensagem) {
        super(mensagem);
    }
}
