package br.com.academiagestao.auth;

/** O código não confere, venceu ou esgotou as tentativas (ADR 0038): peça outro. */
class CodigoInvalidoException extends RuntimeException {

    CodigoInvalidoException() {
        super("Código inválido ou vencido. Confira o e-mail mais recente ou peça outro código.");
    }
}
