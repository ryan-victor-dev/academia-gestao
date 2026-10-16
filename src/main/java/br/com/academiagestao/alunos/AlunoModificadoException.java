package br.com.academiagestao.alunos;

class AlunoModificadoException extends RuntimeException {

    AlunoModificadoException() {
        super("O aluno foi alterado por outra pessoa depois que você o carregou. "
                + "Carregue de novo (GET) e refaça a edição.");
    }
}
