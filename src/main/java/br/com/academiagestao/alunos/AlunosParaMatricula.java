package br.com.academiagestao.alunos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * O que os módulos de matrículas e de treinos podem pedir ao de alunos. Devolve dados, não
 * lança as exceções daqui: quem decide a resposta HTTP é o módulo que chamou.
 */
@Service
public class AlunosParaMatricula {

    private final AlunoRepository repository;

    AlunosParaMatricula(AlunoRepository repository) {
        this.repository = repository;
    }

    /**
     * Trava a linha do aluno até o fim da transação de quem chamou (SELECT ... FOR UPDATE)
     * e diz se ele existe e está ativo. Com a trava, matricular (ou montar uma ficha) e
     * inativar o mesmo aluno ao mesmo tempo não passam juntos pelas checagens: um espera o
     * outro (ADR 0016).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EstadoDoAluno travar(UUID alunoId) {
        return repository.travarPorId(alunoId)
                .map(aluno -> aluno.isAtivo() ? EstadoDoAluno.ATIVO : EstadoDoAluno.INATIVO)
                .orElse(EstadoDoAluno.INEXISTENTE);
    }
}
