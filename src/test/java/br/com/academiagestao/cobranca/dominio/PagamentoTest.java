package br.com.academiagestao.cobranca.dominio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O código da transação do Pix e do cartão do balcão (ADR 0046, item 10). */
class PagamentoTest {

    @Test
    void tiraOsEspacosEPassaParaMaiusculas() {
        assertThat(Pagamento.codigoValido(MeioDePagamento.PIX, " e2e 12\tab\n")).isEqualTo("E2E12AB");
        // O espaço que não quebra (do copiar e colar) também sai.
        assertThat(Pagamento.codigoValido(MeioDePagamento.CARTAO_DEBITO, "12\u00A034")).isEqualTo("1234");
    }

    @ParameterizedTest
    @EnumSource(value = MeioDePagamento.class, names = {"PIX", "CARTAO_DEBITO", "CARTAO_CREDITO"})
    void obrigatorioNoPixENoCartao(MeioDePagamento meio) {
        assertThat(meio.exigeCodigo()).isTrue();
        assertThatThrownBy(() -> Pagamento.codigoValido(meio, null)).isInstanceOf(CobrancaInvalidaException.class)
                .hasMessageContaining("obrigatório");
        assertThatThrownBy(() -> Pagamento.codigoValido(meio, " \t ")).isInstanceOf(CobrancaInvalidaException.class)
                .hasMessageContaining("obrigatório");
    }

    @ParameterizedTest
    @EnumSource(value = MeioDePagamento.class, names = {"PIX", "CARTAO_DEBITO", "CARTAO_CREDITO"},
            mode = EnumSource.Mode.EXCLUDE)
    void asOutrasFormasNaoTemCodigo(MeioDePagamento meio) {
        assertThat(meio.exigeCodigo()).isFalse();
        assertThat(Pagamento.codigoValido(meio, null)).isNull();
        assertThat(Pagamento.codigoValido(meio, "  ")).isNull();
        assertThatThrownBy(() -> Pagamento.codigoValido(meio, "123")).isInstanceOf(CobrancaInvalidaException.class)
                .hasMessageContaining("só do Pix e do cartão");
    }

    @Test
    void ateCemCaracteresDepoisDeLimpo() {
        assertThat(Pagamento.codigoValido(MeioDePagamento.PIX, "a".repeat(100) + "  ")).hasSize(100);
        // Conta caracteres, e não unidades do Java: o banco guarda até 100 caracteres.
        assertThat(Pagamento.codigoValido(MeioDePagamento.PIX, "\uD835\uDC00".repeat(100))).isNotNull();
        assertThatThrownBy(() -> Pagamento.codigoValido(MeioDePagamento.PIX, "a".repeat(101)))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB\u0000C", "AB\u0007", "\u0080AB"})
    void caractereDeControleERecusado(String codigo) {
        assertThatThrownBy(() -> Pagamento.codigoValido(MeioDePagamento.PIX, codigo))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("inválidos");
    }
}
