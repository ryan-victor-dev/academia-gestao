package br.com.academiagestao.cobranca;

public class TransicaoInvalidaException extends RuntimeException {

    public TransicaoInvalidaException(StatusMensalidade origem, StatusMensalidade destino) {
        super("Transição de mensalidade não permitida: %s -> %s".formatted(origem, destino));
    }
}