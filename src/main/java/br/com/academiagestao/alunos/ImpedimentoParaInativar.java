package br.com.academiagestao.alunos;

import java.util.Optional;
import java.util.UUID;

/**
 * Algo de outro módulo que impede inativar um aluno (ex.: matrícula vigente). O módulo de
 * alunos declara a interface e os outros a implementam: assim alunos não depende de
 * matrículas, e matrículas (que já depende de alunos) não forma ciclo (ADR 0016).
 */
public interface ImpedimentoParaInativar {

    /**
     * Chamado com a linha do aluno já travada, dentro da transação da inativação.
     *
     * @return o motivo, em uma frase para o usuário, ou vazio se nada impede
     */
    Optional<String> verificar(UUID alunoId);
}
