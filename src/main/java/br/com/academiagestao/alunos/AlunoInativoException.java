package br.com.academiagestao.alunos;

class AlunoInativoException extends RuntimeException {

    AlunoInativoException() {
        super("O aluno está inativo; reative-o antes");
    }
}
