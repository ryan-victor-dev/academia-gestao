package br.com.academiagestao.alunos;

import java.util.UUID;

class AlunoNaoEncontradoException extends RuntimeException {

    AlunoNaoEncontradoException(UUID id) {
        super("Aluno " + id + " não encontrado");
    }
}
