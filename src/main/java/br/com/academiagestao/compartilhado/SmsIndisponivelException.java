package br.com.academiagestao.compartilhado;

/** A empresa de SMS fora do ar ou recusando o envio. */
public class SmsIndisponivelException extends RuntimeException {

    public SmsIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
