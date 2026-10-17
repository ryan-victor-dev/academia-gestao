package br.com.academiagestao.alunos;

import br.com.academiagestao.auth.LoginsDeAlunos;
import br.com.academiagestao.compartilhado.RecursoModificadoException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlunoServiceTest {

    @Test
    void edicaoQuePerdeACorridaNoBancoViraAlunoModificado() {
        // Simula duas edições simultâneas com o mesmo If-Match: as duas passam pela conferência,
        // mas o UPDATE da segunda não acha mais a versão e o Hibernate lança a exceção de lock.
        AlunoRepository repository = mock(AlunoRepository.class);
        Aluno ana = Aluno.novo(new DadosAluno("Ana", "52998224725", null, null, null, null));
        when(repository.findById(ana.getId())).thenReturn(Optional.of(ana));
        when(repository.saveAndFlush(any())).thenThrow(new ObjectOptimisticLockingFailureException(Aluno.class, ana.getId()));
        AlunoService service = new AlunoService(repository, mock(LoginsDeAlunos.class), List.of());

        assertThatThrownBy(() -> service.atualizar(ana.getId(),
                new DadosAluno("Ana Maria", "52998224725", null, null, null, null), "\"0\""))
                .isInstanceOf(RecursoModificadoException.class);
    }
}
