package br.com.academiagestao.matriculas;

import br.com.academiagestao.cobranca.CobrancasDasMatriculas;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A geração das cobranças das matrículas antigas só roda ao subir quando alguém a liga
 * (ADR 0044, item 20): numa instalação nova, ela varreria todas as matrículas a cada subida.
 * Sem o Spring inteiro: a configuração de teste a desliga explicitamente e esconderia o padrão.
 */
class CobrancasDasMatriculasAntigasAoSubirTest {

    private final WebApplicationContextRunner contexto = new WebApplicationContextRunner()
            .withBean(MatriculaRepository.class, () -> mock(MatriculaRepository.class))
            .withBean(MatriculaService.class, () -> mock(MatriculaService.class))
            .withBean(CobrancasDasMatriculas.class, () -> mock(CobrancasDasMatriculas.class))
            .withUserConfiguration(CobrancasDasMatriculasAntigas.class);

    @Test
    void semAPropriedadeNaoRoda() {
        contexto.run(c -> assertThat(c).doesNotHaveBean(CobrancasDasMatriculasAntigas.class));
    }

    @Test
    void ligadaRoda() {
        contexto.withPropertyValues("academia.cobranca.gerar-das-antigas-ao-subir=true")
                .run(c -> assertThat(c).hasSingleBean(CobrancasDasMatriculasAntigas.class));
    }
}
