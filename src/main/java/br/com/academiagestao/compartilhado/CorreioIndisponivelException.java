package br.com.academiagestao.compartilhado;

/** O servidor de e-mail não respondeu ou recusou a mensagem: tente de novo mais tarde. */
public class CorreioIndisponivelException extends RuntimeException {

    public CorreioIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
