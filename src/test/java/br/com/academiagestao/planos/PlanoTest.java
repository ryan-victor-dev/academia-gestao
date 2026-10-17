package br.com.academiagestao.planos;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanoTest {

    private static DadosPlano dados(String nome, String valor, Integer meses) {
        return new DadosPlano(nome, valor == null ? null : new BigDecimal(valor), meses);
    }

    @Test
    void novoPlanoNasceAtivoComNomeLimpoEValorComDuasCasas() {
        Plano plano = Plano.novo(dados("  Plano   Trimestral ", "119.9", 3));

        assertThat(plano.getId()).isNotNull();
        assertThat(plano.isAtivo()).isTrue();
        assertThat(plano.getNome()).isEqualTo("Plano Trimestral");
        assertThat(plano.getValorMensal()).isEqualByComparingTo("119.90").hasToString("119.90");
        assertThat(plano.getValorTotal()).isEqualByComparingTo("359.70");
        assertThat(plano.getCriadoEm().getNano() % 1000).isZero();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "'  ',    100,         1,  nome",
            "null,    100,         1,  nome",
            "Mensal,  0,           1,  maior que zero",
            "Mensal,  -10,         1,  maior que zero",
            "Mensal,  null,        1,  maior que zero",
            "Mensal,  129.999,     1,  2 casas",
            "Mensal,  100000000,   1,  no máximo",
            "Mensal,  100,         0,  1 a 60",
            "Mensal,  100,         61, 1 a 60",
            "Mensal,  100,         null, 1 a 60",
    })
    void recusaDadosInvalidos(String nome, String valor, Integer meses, String mensagem) {
        assertThatThrownBy(() -> Plano.novo(dados(nome, valor, meses)))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining(mensagem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"129.90", "129.900", "1", "0.01", "99999999.99"})
    void aceitaValoresValidos(String valor) {
        assertThat(Plano.novo(dados("Mensal", valor, 1)).getValorMensal().scale()).isEqualTo(2);
    }

    @Test
    void nomeComMaisDe80CaracteresEhRecusado() {
        assertThatThrownBy(() -> Plano.novo(dados("x".repeat(81), "100", 1)))
                .isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void edicaoInvalidaNaoAlteraNada() {
        Plano plano = Plano.novo(dados("Mensal", "100", 1));

        assertThatThrownBy(() -> plano.atualizar(dados("Mensal Promo", "0", 1)))
                .isInstanceOf(DadosInvalidosException.class);

        assertThat(plano.getNome()).isEqualTo("Mensal");
        assertThat(plano.getValorMensal()).isEqualByComparingTo("100");
    }

    @Test
    void inativarEReativar() {
        Plano plano = Plano.novo(dados("Mensal", "100", 1));
        plano.inativar();
        assertThat(plano.isAtivo()).isFalse();
        plano.reativar();
        assertThat(plano.isAtivo()).isTrue();
    }
}
