package br.com.academiagestao.compartilhado;

import java.util.Optional;
import java.util.UUID;

/**
 * O que o primeiro acesso do aluno (ADR 0039) precisa saber do cadastro: o aluno ativo com este
 * CPF e este e-mail. Fica no compartilhado porque o auth não depende do módulo de alunos (ADR
 * 0015): o auth pergunta por aqui, e o módulo de alunos responde.
 */
public interface CadastroParaPrimeiroAcesso {

    /**
     * O aluno ativo com este CPF (com ou sem pontuação) e este e-mail (sem diferenciar
     * maiúsculas). CPF inválido, aluno inativo, sem e-mail ou com outro e-mail: vazio.
     */
    Optional<AlunoDoCadastro> ativoCom(String cpf, String email);

    /** @param email normalizado, como o do login (minúsculo, sem espaços nas pontas) */
    record AlunoDoCadastro(UUID id, String nome, String email) {
    }
}
