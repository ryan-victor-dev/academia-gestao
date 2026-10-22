package br.com.academiagestao.treinos;

import br.com.academiagestao.alunos.AoMudarASituacaoDoAluno;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** A ficha atual acompanha se o aluno está ativo, para a lista de revisões vencidas (V33). */
@Component
class SituacaoDoAlunoNosTreinos implements AoMudarASituacaoDoAluno {

    private final FichaRepository repository;

    SituacaoDoAlunoNosTreinos(FichaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void inativado(UUID alunoId) {
        repository.marcarAlunoAtivo(alunoId, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reativado(UUID alunoId) {
        repository.marcarAlunoAtivo(alunoId, true);
    }
}
