package br.com.academiagestao.alunos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * Nome de cada aluno ativo entre {@code ids}; inativos e inexistentes ficam de fora. Para
     * listas da equipe (as revisões vencidas dos treinos), que mostram quem é o aluno.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> nomesDosAtivos(Collection<UUID> ids) {
        return repository.findAllById(ids).stream()
                .filter(Aluno::isAtivo)
                .collect(Collectors.toMap(Aluno::getId, Aluno::getNome));
    }
}
