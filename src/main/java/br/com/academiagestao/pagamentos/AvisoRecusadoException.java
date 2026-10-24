package br.com.academiagestao.pagamentos;

/** Um aviso sem a prova de que veio da empresa de pagamentos (token ou assinatura). */
public class AvisoRecusadoException extends RuntimeException {

    public AvisoRecusadoException(String mensagem) {
        super(mensagem);
    }
}
