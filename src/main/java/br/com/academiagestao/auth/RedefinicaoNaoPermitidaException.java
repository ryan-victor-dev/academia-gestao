package br.com.academiagestao.auth;

/** Regra da hierarquia: a senha de um ADMIN não é redefinida por outra pessoa. */
class RedefinicaoNaoPermitidaException extends RuntimeException {

    RedefinicaoNaoPermitidaException() {
        super("A senha de um ADMIN não pode ser redefinida por outra pessoa. "
                + "Cada ADMIN troca a própria em POST /api/auth/trocar-senha.");
    }
}
