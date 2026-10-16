package br.com.academiagestao.alunos;

import java.time.LocalDate;

/** Dados editáveis de um aluno, usados tanto no cadastro quanto na edição. */
public record DadosAluno(
        String nome,
        String cpf,
        String email,
        String telefone,
        LocalDate dataNascimento,
        String observacoes) {
}
