package br.com.academiagestao.alunos;

import java.time.LocalDate;

/** Uma data de nascimento qualquer, para os testes que cadastram aluno: ela é obrigatória no cadastro novo (ADR 0048). */
public final class NascimentoParaTeste {

    public static final LocalDate NASCIMENTO = LocalDate.of(1990, 5, 10);

    private NascimentoParaTeste() {
    }
}
