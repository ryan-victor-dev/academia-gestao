package br.com.academiagestao.matriculas;

import java.util.UUID;

class MatriculaNaoEncontradaException extends RuntimeException {

    MatriculaNaoEncontradaException(UUID id) {
        super("Matrícula " + id + " não encontrada");
    }
}
