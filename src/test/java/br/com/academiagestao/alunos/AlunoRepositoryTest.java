package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AlunoRepositoryTest {

    @Autowired
    AlunoRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        // usuarios.aluno_id aponta para alunos: limpa os logins de aluno primeiro.
        jdbc.update("DELETE FROM usuarios WHERE aluno_id IS NOT NULL");
        repository.deleteAll();
    }

    @Test
    void salvaEEncontraPeloCpf() {
        repository.saveAndFlush(Aluno.novo(new DadosAluno("Ana", "529.982.247-25", null, null, null, null)));

        assertThat(repository.findByCpf("52998224725"))
                .get()
                .extracting(Aluno::getNome, Aluno::isAtivo)
                .containsExactly("Ana", true);
    }

    @Test
    void bancoRecusaCpfRepetido() {
        repository.saveAndFlush(Aluno.novo(new DadosAluno("Ana", "52998224725", null, null, null, null)));

        assertThatThrownBy(() -> repository.saveAndFlush(
                Aluno.novo(new DadosAluno("Outra Ana", "529.982.247-25", null, null, null, null))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bancoRecusaCpfForaDoFormatoMesmoSemPassarPeloJava() {
        // Simula alguém gravando direto no banco, sem passar pela classe Aluno.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Ana', '529.982.24')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
