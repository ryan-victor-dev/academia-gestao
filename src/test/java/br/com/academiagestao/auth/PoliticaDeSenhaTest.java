package br.com.academiagestao.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PoliticaDeSenhaTest {

    @Test
    void aceitaSenhaDentroDosLimites() {
        assertThat(PoliticaDeSenha.problemaCom("a".repeat(12))).isEmpty();
        assertThat(PoliticaDeSenha.problemaCom("a".repeat(72))).isEmpty();
    }

    @Test
    void recusaSenhaCurtaOuNula() {
        assertThat(PoliticaDeSenha.problemaCom("a".repeat(11))).get().asString().contains("12");
        assertThat(PoliticaDeSenha.problemaCom(null)).isPresent();
    }

    @Test
    void contaBytesENaoCaracteres() {
        // "é" ocupa 2 bytes em UTF-8: 37 caracteres = 74 bytes, acima do limite do BCrypt.
        assertThat(PoliticaDeSenha.problemaCom("é".repeat(37))).get().asString().contains("72");
    }
}
