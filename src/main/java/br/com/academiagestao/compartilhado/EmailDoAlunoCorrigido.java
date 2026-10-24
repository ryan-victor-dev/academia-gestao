package br.com.academiagestao.compartilhado;

import java.util.UUID;

/**
 * O aluno corrigiu, no primeiro acesso, o e-mail do próprio login (ADR 0038). O módulo de
 * alunos ouve e atualiza o cadastro, na mesma transação: o auth não depende de outros módulos.
 */
public record EmailDoAlunoCorrigido(UUID alunoId, String email) {
}
