package br.com.academiagestao.pagamentos;

/** A empresa de pagamentos não respondeu, ou respondeu com erro: tente de novo mais tarde. */
public class ProvedorIndisponivelException extends RuntimeException {

    public ProvedorIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
