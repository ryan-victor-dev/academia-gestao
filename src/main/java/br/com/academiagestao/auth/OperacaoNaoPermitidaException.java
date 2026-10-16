package br.com.academiagestao.auth;

/** A operação quebraria uma regra de segurança da equipe (ex.: ficar sem nenhum ADMIN). */
class OperacaoNaoPermitidaException extends RuntimeException {

    OperacaoNaoPermitidaException(String mensagem) {
        super(mensagem);
    }
}
