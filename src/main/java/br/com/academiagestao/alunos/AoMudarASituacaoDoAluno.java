package br.com.academiagestao.alunos;

import java.util.UUID;

/**
 * Outro módulo que guarda algo que depende de o aluno estar ativo (ex.: os treinos, para a
 * lista de revisões vencidas). Como {@link ImpedimentoParaInativar}: alunos declara, os
 * outros implementam, sem ciclo. Chamado dentro da transação da inativação ou da
 * reativação: se falhar, nada muda.
 */
public interface AoMudarASituacaoDoAluno {

    void inativado(UUID alunoId);

    void reativado(UUID alunoId);
}
