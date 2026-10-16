package br.com.academiagestao.alunos;

class CpfJaCadastradoException extends RuntimeException {

    CpfJaCadastradoException() {
        super("Já existe um aluno com este CPF");
    }
}
