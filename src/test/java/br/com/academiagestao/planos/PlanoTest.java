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

    @Test
    void recusaDadosNulos() {
        assertThatThrownBy(() -> Plano.novo(null)).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void limitesExatosDeNomeEDuracao() {
        assertThat(Plano.novo(dados("x".repeat(80), "100", 1)).getNome()).hasSize(80);
        assertThat(Plano.novo(dados("Mensal", "100", 60)).getDuracaoMeses()).isEqualTo(60);
        assertThat(Plano.novo(dados("Mensal", "100", 1)).getDuracaoMeses()).isEqualTo(1);
    }

    // Fidelidade e forma de pagamento (ADR 0018)

    private static DadosPlano comRegras(Boolean fidelidade, String multa, FormaPagamento forma) {
        return new DadosPlano("Anual", new BigDecimal("99.90"), 12, fidelidade,
                multa == null ? null : new BigDecimal(multa), forma);
    }

    @Test
    void semInformarFicaSemFidelidadeEMensal() {
        Plano plano = Plano.novo(dados("Mensal", "100", 1));

        assertThat(plano.isFidelidade()).isFalse();
        assertThat(plano.getPercentualMulta()).isNull();
        assertThat(plano.getFormaPagamento()).isEqualTo(FormaPagamento.MENSAL);
    }

    @Test
    void comFidelidadeGuardaOPercentualComDuasCasas() {
        Plano plano = Plano.novo(comRegras(true, "12.5", FormaPagamento.A_VISTA));

        assertThat(plano.isFidelidade()).isTrue();
        assertThat(plano.getPercentualMulta()).hasToString("12.50");
        assertThat(plano.getFormaPagamento()).isEqualTo(FormaPagamento.A_VISTA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "100", "100.00", "20"})
    void percentuaisNosLimitesSaoAceitos(String multa) {
        assertThat(Plano.novo(comRegras(true, multa, null)).getPercentualMulta().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "null,   0,01 a 100",
            "0,      0,01 a 100",
            "-5,     0,01 a 100",
            "100.01, 0,01 a 100",
            "12.345, 2 casas",
    })
    void comFidelidadeOPercentualEhObrigatorioEValido(String multa, String mensagem) {
        assertThatThrownBy(() -> Plano.novo(comRegras(true, multa, null)))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining(mensagem);
    }

    @Test
    void semFidelidadeNaoTemPercentual() {
        assertThatThrownBy(() -> Plano.novo(comRegras(false, "20", null)))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("só existe em plano com fidelidade");
        assertThatThrownBy(() -> Plano.novo(comRegras(null, "20", null)))
                .isInstanceOf(DadosInvalidosException.class);
        assertThat(Plano.novo(comRegras(false, null, null)).isFidelidade()).isFalse();
    }

    @Test
    void tirarAFidelidadeApagaOPercentual() {
        Plano plano = Plano.novo(comRegras(true, "20", FormaPagamento.A_VISTA));

        plano.atualizar(comRegras(false, null, null));

        assertThat(plano.isFidelidade()).isFalse();
        assertThat(plano.getPercentualMulta()).isNull();
        assertThat(plano.getFormaPagamento()).isEqualTo(FormaPagamento.MENSAL);
    }

    @Test
    void edicaoInvalidaDaFidelidadeNaoAlteraNada() {
        Plano plano = Plano.novo(comRegras(true, "20", FormaPagamento.A_VISTA));

        assertThatThrownBy(() -> plano.atualizar(new DadosPlano("Outro", new BigDecimal("50"), 1, true, null,
                FormaPagamento.MENSAL)))
                .isInstanceOf(DadosInvalidosException.class);

        assertThat(plano.getNome()).isEqualTo("Anual");
        assertThat(plano.getPercentualMulta()).isEqualByComparingTo("20");
        assertThat(plano.getFormaPagamento()).isEqualTo(FormaPagamento.A_VISTA);
    }
}
