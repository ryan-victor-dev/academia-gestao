package br.com.academiagestao.treinos;

import java.util.UUID;

class FichaNaoEncontradaException extends RuntimeException {

    private FichaNaoEncontradaException(String mensagem) {
        super(mensagem);
    }

    static FichaNaoEncontradaException comId(UUID id) {
        return new FichaNaoEncontradaException("Ficha " + id + " não encontrada");
    }

    static FichaNaoEncontradaException semAtual(UUID alunoId) {
        return new FichaNaoEncontradaException("O aluno " + alunoId + " não tem ficha atual");
    }
}
