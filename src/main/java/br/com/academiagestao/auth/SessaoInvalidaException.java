package br.com.academiagestao.auth;

/**
 * Renovação recusada. De propósito não diz o motivo (sem cookie, vencida, encerrada, revogada
 * ou segredo reaproveitado): a resposta é a mesma em todos os casos.
 */
class SessaoInvalidaException extends RuntimeException {

    SessaoInvalidaException() {
        super("Sessão inválida");
    }
}
