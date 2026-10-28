package br.com.academiagestao.pagamentos;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O simulado não sobe num servidor de verdade (diagnóstico de 27/10, M1): o aluno pagaria sem pagar. */
class TravaDoSimuladoTest {

    @ParameterizedTest
    @ValueSource(strings = {"academia.com.br", "app.academia.com.br", "localhost.academia.com.br"})
    void recusaUmDominioDeVerdade(String dominio) {
        assertThatThrownBy(() -> TravaDoSimulado.conferir(dominio))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOMINIO=" + dominio);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"localhost", "  "})
    void aceitaSemDominioOuLocalhost(String dominio) {
        assertThatCode(() -> TravaDoSimulado.conferir(dominio)).doesNotThrowAnyException();
    }
}
