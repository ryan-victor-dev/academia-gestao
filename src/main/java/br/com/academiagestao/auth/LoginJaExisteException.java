package br.com.academiagestao.auth;

/** O aluno já tem login, ou o e-mail já é usado por outro login. */
public class LoginJaExisteException extends RuntimeException {

    public LoginJaExisteException(String mensagem) {
        super(mensagem);
    }
}
