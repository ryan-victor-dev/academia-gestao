package br.com.academiagestao.cobranca.dominio;

/** Dado de entrada recusado por uma regra da cobrança (vira 400 na API). */
public class CobrancaInvalidaException extends RuntimeException {

    public CobrancaInvalidaException(String mensagem) {
        super(mensagem);
    }
}
