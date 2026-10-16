package br.com.academiagestao.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GeradorDeSenhaTemporariaTest {

    @Test
    void formatoEmQuatroGruposSemCaracteresAmbiguos() {
        String senha = GeradorDeSenhaTemporaria.gerar();

        assertThat(senha).matches("[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}");
        assertThat(senha).doesNotContainAnyWhitespaces().doesNotContain("0", "1", "O", "l", "I");
    }

    @Test
    void passaNaPoliticaDeSenha() {
        assertThat(PoliticaDeSenha.problemaCom(GeradorDeSenhaTemporaria.gerar())).isEmpty();
    }

    @Test
    void naoRepeteSenhas() {
        Set<String> senhas = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            senhas.add(GeradorDeSenhaTemporaria.gerar());
        }
        assertThat(senhas).hasSize(1000);
    }
}
