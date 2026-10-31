package br.com.academiagestao.folha;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CombinadoTest {

    @Test
    void valorComDuasCasas() {
        assertThat(new Combinado(Vinculo.CLT, new BigDecimal("2500")).valor()).isEqualByComparingTo("2500.00")
                .hasToString("2500.00");
        assertThat(new Combinado(Vinculo.MEI, new BigDecimal("1800.50")).valor()).hasToString("1800.50");
    }

    @Test
    void semValorFixo() {
        assertThat(new Combinado(Vinculo.AUTONOMO, null).valor()).isNull();
    }

    @Test
    void vinculoObrigatorio() {
        assertThatThrownBy(() -> new Combinado(null, BigDecimal.TEN))
                .isInstanceOf(DadosInvalidosException.class).hasMessage("vinculo: é obrigatório (CLT, MEI ou AUTONOMO)");
    }

    @ParameterizedTest
    @CsvSource({
            "0, valorCombinado: precisa ser maior que zero",
            "-1, valorCombinado: precisa ser maior que zero",
            "10.001, valorCombinado: até 2 casas decimais",
            "1000000.00, valorCombinado: pode ser no máximo 999999.99",
    })
    void valorInvalido(String valor, String mensagem) {
        assertThatThrownBy(() -> new Combinado(Vinculo.CLT, new BigDecimal(valor)))
                .isInstanceOf(DadosInvalidosException.class).hasMessage(mensagem);
    }

    @Test
    void limites() {
        assertThat(new Combinado(Vinculo.CLT, new BigDecimal("999999.99")).valor()).hasToString("999999.99");
        assertThat(new Combinado(Vinculo.CLT, new BigDecimal("0.01")).valor()).hasToString("0.01");
        // Zeros à direita além da segunda casa não são casas a mais.
        assertThat(new Combinado(Vinculo.CLT, new BigDecimal("10.500")).valor()).hasToString("10.50");
    }
}
