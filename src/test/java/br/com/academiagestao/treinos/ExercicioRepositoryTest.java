package br.com.academiagestao.treinos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ExercicioRepositoryTest {

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    ExercicioRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        limpeza.apagarTudo();
    }

    private static Exercicio exercicio(String nome, GrupoMuscular grupo) {
        return Exercicio.novo(new DadosExercicio(nome, grupo, null), Instant.parse("2026-10-21T12:00:00Z"));
    }

    @Test
    void salvaELeDeVolta() {
        Exercicio salvo = repository.saveAndFlush(
                Exercicio.novo(new DadosExercicio("Supino", GrupoMuscular.PEITO, "Barra até o peito"),
                        Instant.parse("2026-10-21T12:00:00.123456Z")));

        Exercicio lido = repository.findById(salvo.getId()).orElseThrow();
        assertThat(lido.getNome()).isEqualTo("Supino");
        assertThat(lido.getGrupoMuscular()).isEqualTo(GrupoMuscular.PEITO);
        assertThat(lido.getObservacao()).isEqualTo("Barra até o peito");
        assertThat(lido.getCriadoEm()).isEqualTo(Instant.parse("2026-10-21T12:00:00.123456Z"));
        assertThat(lido.getVersao()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Supino", "SUPINO", "supino", "Supíno", "SÚPINO"})
    void bancoRecusaDoisAtivosComOMesmoNome(String outroNome) {
        repository.saveAndFlush(exercicio("Supino", GrupoMuscular.PEITO));

        assertThatThrownBy(() -> repository.saveAndFlush(exercicio(outroNome, GrupoMuscular.OMBROS)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inativoPodeTerOMesmoNomeDeUmAtivo() {
        Exercicio antigo = exercicio("Supino", GrupoMuscular.PEITO);
        antigo.inativar();
        repository.saveAndFlush(antigo);

        assertThatCode(() -> repository.saveAndFlush(exercicio("Supino", GrupoMuscular.PEITO)))
                .doesNotThrowAnyException();
    }

    @Test
    void consultaDeNomeRepetidoUsaAMesmaRegraDoBanco() {
        Exercicio supino = repository.saveAndFlush(exercicio("Supino", GrupoMuscular.PEITO));
        Exercicio inativo = exercicio("Remada", GrupoMuscular.COSTAS);
        inativo.inativar();
        repository.saveAndFlush(inativo);

        assertThat(repository.existeOutroAtivoComNome("SUPÍNO", UUID.randomUUID())).isTrue();
        // O próprio exercício não conta (edição sem trocar o nome).
        assertThat(repository.existeOutroAtivoComNome("Supino", supino.getId())).isFalse();
        // Inativo não conta.
        assertThat(repository.existeOutroAtivoComNome("Remada", UUID.randomUUID())).isFalse();
    }

    @Test
    void listaPorGrupoEAtivosEmOrdemDeNome() {
        repository.saveAndFlush(exercicio("Supino", GrupoMuscular.PEITO));
        repository.saveAndFlush(exercicio("Crucifixo", GrupoMuscular.PEITO));
        repository.saveAndFlush(exercicio("Remada", GrupoMuscular.COSTAS));
        Exercicio inativo = exercicio("Voador", GrupoMuscular.PEITO);
        inativo.inativar();
        repository.saveAndFlush(inativo);

        assertThat(repository.listar(GrupoMuscular.PEITO, false)).extracting(Exercicio::getNome)
                .containsExactly("Crucifixo", "Supino");
        assertThat(repository.listar(GrupoMuscular.PEITO, true)).extracting(Exercicio::getNome)
                .containsExactly("Crucifixo", "Supino", "Voador");
        assertThat(repository.listar(null, false)).extracting(Exercicio::getNome)
                .containsExactly("Crucifixo", "Remada", "Supino");
    }

    /** O banco confere o que a entidade já confere, para nenhuma gravação por fora escapar. */
    @Test
    void bancoConfereOsCampos() {
        UUID id = repository.saveAndFlush(exercicio("Supino", GrupoMuscular.PEITO)).getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE exercicios SET nome = '  ' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE exercicios SET grupo_muscular = 'PESCOCO' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE exercicios SET observacao = ' ' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.update("UPDATE exercicios SET grupo_muscular = 'CARDIO', observacao = NULL"
                + " WHERE id = ?", id)).doesNotThrowAnyException();
    }

    /** Cada valor do enum cabe no banco: um grupo novo no Java sem a migração seria pego aqui. */
    @Test
    void todosOsGruposCabemNoBanco() {
        for (GrupoMuscular grupo : GrupoMuscular.values()) {
            assertThatCode(() -> repository.saveAndFlush(exercicio("Exercício " + grupo, grupo)))
                    .as(grupo.name()).doesNotThrowAnyException();
        }
    }
}
