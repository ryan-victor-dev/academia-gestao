package br.com.academiagestao.planos;

import java.util.UUID;

class PlanoNaoEncontradoException extends RuntimeException {

    PlanoNaoEncontradoException(UUID id) {
        super("Plano " + id + " não encontrado");
    }
}
