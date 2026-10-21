package br.com.academiagestao.treinos;

import java.util.UUID;

class ExercicioNaoEncontradoException extends RuntimeException {

    ExercicioNaoEncontradoException(UUID id) {
        super("Exercício " + id + " não encontrado");
    }
}
