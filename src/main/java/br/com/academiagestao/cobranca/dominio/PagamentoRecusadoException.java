package br.com.academiagestao.cobranca.dominio;

/**
 * O valor informado não é o devido no dia (ADR 0024: só o valor exato). Leva o devido para
 * a resposta: quem abriu a tela na véspera vê o valor de hoje.
 */
public class PagamentoRecusadoException extends RuntimeException {

    private final ValorDevido devido;

    public PagamentoRecusadoException(ValorDevido devido) {
        super("O valor devido hoje é " + devido.total());
        this.devido = devido;
    }

    public ValorDevido getDevido() { return devido; }
}
