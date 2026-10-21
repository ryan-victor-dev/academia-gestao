package br.com.academiagestao.treinos;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExercicioTest {

    private static final Instant AGORA = Instant.parse("2026-10-21T12:00:00.123456789Z");

    private static Exercicio novo(String nome, GrupoMuscular grupo, String observacao) {
        return Exercicio.novo(new DadosExercicio(nome, grupo, observacao), AGORA);
    }

    @Test
    void novoNasceAtivoComONomeLimpo() {
        Exercicio supino = novo("  Supino   reto ", GrupoMuscular.PEITO, "  Descer a barra até o peito  ");

        assertThat(supino.getId()).isNotNull();
        assertThat(supino.getNome()).isEqualTo("Supino reto");
        assertThat(supino.getGrupoMuscular()).isEqualTo(GrupoMuscular.PEITO);
        assertThat(supino.getObservacao()).isEqualTo("Descer a barra até o peito");
        assertThat(supino.isAtivo()).isTrue();
        // O banco guarda microssegundos: o carimbo já nasce assim, igual ao que é lido de volta.
        assertThat(supino.getCriadoEm()).isEqualTo(Instant.parse("2026-10-21T12:00:00.123456Z"));
    }

    @Test
    void observacaoEmBrancoFicaVazia() {
        assertThat(novo("Supino", GrupoMuscular.PEITO, "   ").getObservacao()).isNull();
        assertThat(novo("Supino", GrupoMuscular.PEITO, null).getObservacao()).isNull();
    }

    @Test
    void dadosInvalidosSaoRecusados() {
        assertThatThrownBy(() -> Exercicio.novo(null, AGORA)).isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> novo("   ", GrupoMuscular.PEITO, null))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("nome");
        assertThatThrownBy(() -> novo("x".repeat(81), GrupoMuscular.PEITO, null))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("80");
        assertThat(novo("x".repeat(80), GrupoMuscular.PEITO, null).getNome()).hasSize(80);
        assertThatThrownBy(() -> novo("Supino", null, null))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("grupo");
        assertThatThrownBy(() -> novo("Supino", GrupoMuscular.PEITO, "x".repeat(501)))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("500");
        assertThat(novo("Supino", GrupoMuscular.PEITO, "x".repeat(500)).getObservacao()).hasSize(500);
    }

    /** Valida tudo antes de alterar: com um campo inválido, nada muda. */
    @Test
    void atualizacaoInvalidaNaoMudaNada() {
        Exercicio supino = novo("Supino", GrupoMuscular.PEITO, "Cuidado com o ombro");

        assertThatThrownBy(() -> supino.atualizar(new DadosExercicio("Supino inclinado", null, "Outra")))
                .isInstanceOf(DadosInvalidosException.class);

        assertThat(supino.getNome()).isEqualTo("Supino");
        assertThat(supino.getObservacao()).isEqualTo("Cuidado com o ombro");

        supino.atualizar(new DadosExercicio("Supino inclinado", GrupoMuscular.OMBROS, null));
        assertThat(supino.getNome()).isEqualTo("Supino inclinado");
        assertThat(supino.getGrupoMuscular()).isEqualTo(GrupoMuscular.OMBROS);
        assertThat(supino.getObservacao()).isNull();
    }

    @Test
    void inativarEReativar() {
        Exercicio supino = novo("Supino", GrupoMuscular.PEITO, null);
        supino.inativar();
        assertThat(supino.isAtivo()).isFalse();
        supino.reativar();
        assertThat(supino.isAtivo()).isTrue();
    }
}
