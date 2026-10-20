package br.com.academiagestao.cobranca.dominio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Acréscimo, juros e bloqueio (ADR 0024). */
class RegrasDeAtrasoTest {

    private static final LocalDate VENCIMENTO = LocalDate.of(2026, 11, 10);

    static Cobranca cobranca(String valor) {
        return Cobranca.nova(UUID.randomUUID(), UUID.randomUUID(),
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, new BigDecimal(valor), VENCIMENTO));
    }

    private static RegrasDeAtraso regras(TipoAcrescimo tipo, String acrescimo, String juros, int dias) {
        return new RegrasDeAtraso(tipo, new BigDecimal(acrescimo), new BigDecimal(juros), dias);
    }

    private static ValorDevido devido(RegrasDeAtraso regras, String valor, int diasDepois) {
        return regras.devido(cobranca(valor), VENCIMENTO.plusDays(diasDepois));
    }

    @Test
    void padraoDecididoPeloDono() {
        assertThat(RegrasDeAtraso.PADRAO)
                .isEqualTo(regras(TipoAcrescimo.PERCENTUAL, "2", "1", 10));
        assertThat(RegrasDeAtraso.PADRAO.acrescimo()).isEqualByComparingTo("2");
        assertThat(RegrasDeAtraso.PADRAO.jurosAoMes()).isEqualByComparingTo("1");
        assertThat(RegrasDeAtraso.PADRAO.diasParaBloquear()).isEqualTo(10);
    }

    @Test
    void emDiaPagaSoOValor() {
        ValorDevido noVencimento = devido(RegrasDeAtraso.PADRAO, "100", 0);
        assertThat(noVencimento.total()).isEqualByComparingTo("100");
        assertThat(noVencimento.acrescimo()).isEqualByComparingTo("0").hasToString("0.00");
        assertThat(noVencimento.juros()).isEqualByComparingTo("0").hasToString("0.00");
        assertThat(noVencimento.diasDeAtraso()).isZero();
        assertThat(devido(RegrasDeAtraso.PADRAO, "100", -5).total()).isEqualByComparingTo("100");
    }

    /** Sem tolerância: o dia seguinte ao vencimento já tem acréscimo e um dia de juros. */
    @Test
    void diaSeguinteJaEhAtraso() {
        ValorDevido umDia = devido(RegrasDeAtraso.PADRAO, "100", 1);
        assertThat(umDia.diasDeAtraso()).isEqualTo(1);
        assertThat(umDia.acrescimo()).isEqualByComparingTo("2.00");
        // 100 × 1% × 1/30 = 0,0333...
        assertThat(umDia.juros()).isEqualByComparingTo("0.03");
        assertThat(umDia.total()).isEqualByComparingTo("102.03");
    }

    @ParameterizedTest
    @CsvSource({"30, 1.00", "45, 1.50", "60, 2.00", "3, 0.10"})
    void jurosProporcionaisAosDias(int dias, String juros) {
        assertThat(devido(RegrasDeAtraso.PADRAO, "100", dias).juros()).isEqualByComparingTo(juros);
    }

    @Test
    void jurosArredondamMeioParaCima() {
        // 15 × 1% × 1/30 = 0,005: meio centavo sobe.
        assertThat(devido(RegrasDeAtraso.PADRAO, "15", 1).juros()).isEqualByComparingTo("0.01");
        assertThat(devido(regras(TipoAcrescimo.PERCENTUAL, "2", "2.5", 10), "100", 30).juros())
                .isEqualByComparingTo("2.50");
    }

    @Test
    void semJurosNemAcrescimoConfigurados() {
        ValorDevido devido = devido(regras(TipoAcrescimo.VALOR, "0", "0", 10), "100", 20);
        assertThat(devido.total()).isEqualByComparingTo("100");
    }

    /** O teto de 2% do CDC: R$ 7 numa mensalidade de R$ 100 vira R$ 2 (decisão do dono). */
    @ParameterizedTest
    @CsvSource({"7, 100, 2.00", "1.50, 100, 1.50", "2, 100, 2.00", "7, 1000, 7.00", "7, 349.99, 7.00",
            "7, 300, 6.00", "7, 99.99, 2.00"})
    void acrescimoEmReaisLimitadoA2Porcento(String configurado, String valor, String cobrado) {
        assertThat(devido(regras(TipoAcrescimo.VALOR, configurado, "0", 10), valor, 1).acrescimo())
                .isEqualByComparingTo(cobrado);
    }

    @ParameterizedTest
    @CsvSource({"1, 100, 1.00", "2, 99.99, 2.00", "0.5, 150, 0.75", "1.25, 10, 0.13"})
    void acrescimoPercentual(String percentual, String valor, String cobrado) {
        assertThat(devido(regras(TipoAcrescimo.PERCENTUAL, percentual, "0", 10), valor, 1).acrescimo())
                .isEqualByComparingTo(cobrado);
    }

    @Test
    void soCobrancaEmAbertoTemDevido() {
        Cobranca paga = cobranca("100");
        paga.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO, new BigDecimal("100"), MeioDePagamento.PIX,
                UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> RegrasDeAtraso.PADRAO.devido(paga, VENCIMENTO.plusDays(5)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(RegrasDeAtraso.diasDeAtraso(paga, VENCIMENTO.plusDays(5))).isZero();
    }

    /** "Depois de 10 dias": no 10º dia de atraso ainda treina; no 11º, não. */
    @Test
    void bloqueiaDepoisDosDiasTolerados() {
        assertThat(RegrasDeAtraso.PADRAO.bloqueia(cobranca("100"), VENCIMENTO.plusDays(10))).isFalse();
        assertThat(RegrasDeAtraso.PADRAO.bloqueia(cobranca("100"), VENCIMENTO.plusDays(11))).isTrue();
        RegrasDeAtraso naHora = regras(TipoAcrescimo.PERCENTUAL, "2", "1", 0);
        assertThat(naHora.bloqueia(cobranca("100"), VENCIMENTO)).isFalse();
        assertThat(naHora.bloqueia(cobranca("100"), VENCIMENTO.plusDays(1))).isTrue();
    }

    @Test
    void cobrancaPagaNaoBloqueia() {
        Cobranca paga = cobranca("100");
        paga.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO, new BigDecimal("100"), MeioDePagamento.PIX,
                UUID.randomUUID(), Instant.now());
        assertThat(RegrasDeAtraso.PADRAO.bloqueia(paga, VENCIMENTO.plusDays(60))).isFalse();
    }

    @Test
    void faixasAceitas() {
        regras(TipoAcrescimo.PERCENTUAL, "0", "0", 0);
        regras(TipoAcrescimo.PERCENTUAL, "2", "10", 90);
        regras(TipoAcrescimo.VALOR, "1000", "0.01", 1);
        assertThat(regras(TipoAcrescimo.VALOR, "7", "1", 10).acrescimo()).hasToString("7.00");
        assertThat(regras(TipoAcrescimo.VALOR, "7", "1", 10).jurosAoMes()).hasToString("1.00");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "nulo", value = {
            "PERCENTUAL, 2.01, 1, 10, 0 a 2%",
            "PERCENTUAL, -0.01, 1, 10, 0 a 2%",
            "PERCENTUAL, 1.005, 1, 10, 0 a 2%",
            "PERCENTUAL, nulo, 1, 10, 0 a 2%",
            "VALOR, 1000.01, 1, 10, 0 a 1000",
            "VALOR, -1, 1, 10, 0 a 1000",
            "VALOR, 7.001, 1, 10, 0 a 1000",
            "VALOR, 7, 10.01, 10, juros",
            "VALOR, 7, -0.01, 10, juros",
            "VALOR, 7, 0.001, 10, juros",
            "VALOR, 7, nulo, 10, juros",
            "VALOR, 7, 1, -1, dias",
            "VALOR, 7, 1, 91, dias",
            "nulo, 7, 1, 10, tipo"})
    void recusaForaDasFaixas(TipoAcrescimo tipo, String acrescimo, String juros, int dias, String mensagem) {
        assertThatThrownBy(() -> new RegrasDeAtraso(tipo, acrescimo == null ? null : new BigDecimal(acrescimo),
                juros == null ? null : new BigDecimal(juros), dias))
                .isInstanceOf(CobrancaInvalidaException.class)
                .hasMessageContaining(mensagem);
    }
}
