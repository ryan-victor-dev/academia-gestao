package br.com.academiagestao.auth;

/** Login recusado. De propósito não diz se o problema foi o e-mail, a senha ou o usuário inativo. */
class CredenciaisInvalidasException extends RuntimeException {

    CredenciaisInvalidasException() {
        super("Credenciais inválidas");
    }
}
