package br.com.academiagestao.cobranca.dominio;

import br.com.academiagestao.cobranca.dominio.Caixa.Fechamento;
import br.com.academiagestao.cobranca.dominio.Caixa.Reabertura;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As regras do caixa do dia (ADR 0031), sem banco. */
class CaixaTest {

    private static final LocalDate DIA = LocalDate.of(2026, 11, 3);
    private static final UUID RECEPCAO = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant AGORA = Instant.parse("2026-11-03T12:00:00.123456789Z");

    private static Caixa aberto(String troco) {
        return Caixa.abrir(DIA, new BigDecimal(troco), RECEPCAO, AGORA);
    }

    private static Fechamento fechar(Caixa caixa, String contado, String recebido, String estornado, String justificativa) {
        return caixa.fechar(new BigDecimal(contado), new BigDecimal(recebido), new BigDecimal(estornado), justificativa,
                RECEPCAO, AGORA);
    }

    @Test
    void abreComOTroco() {
        Caixa caixa = aberto("50");

        assertThat(caixa.getDia()).isEqualTo(DIA);
        assertThat(caixa.getTroco()).isEqualTo(new BigDecimal("50.00"));
        assertThat(caixa.getSituacao()).isEqualTo(Caixa.Situacao.ABERTO);
        assertThat(caixa.getAbertoPor()).isEqualTo(RECEPCAO);
        // O banco guarda microssegundos.
        assertThat(caixa.getAbertoEm()).isEqualTo(Instant.parse("2026-11-03T12:00:00.123456Z"));
        assertThatCode(caixa::conferirQueRecebe).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "100000.01", "10.001"})
    void trocoForaDaFaixaRecusado(String troco) {
        assertThatThrownBy(() -> aberto(troco)).isInstanceOf(CobrancaInvalidaException.class)
                .hasMessageContaining("troco");
    }

    @Test
    void trocoNasPontasDaFaixa() {
        assertThat(aberto("0").getTroco()).isEqualTo(new BigDecimal("0.00"));
        assertThat(aberto("100000").getTroco()).isEqualTo(new BigDecimal("100000.00"));
        assertThat(aberto("10.500").getTroco()).isEqualTo(new BigDecimal("10.50"));
        assertThatThrownBy(() -> Caixa.abrir(DIA, null, RECEPCAO, AGORA)).isInstanceOf(CobrancaInvalidaException.class);
    }

    /** Esperado = troco + dinheiro recebido − dinheiro estornado; diferença = contado − esperado. */
    @Test
    void fechaSemDiferencaSemJustificativa() {
        Caixa caixa = aberto("50");

        Fechamento fechamento = fechar(caixa, "130", "100", "20", "   ");

        assertThat(fechamento.esperado()).isEqualTo(new BigDecimal("130.00"));
        assertThat(fechamento.contado()).isEqualTo(new BigDecimal("130.00"));
        assertThat(fechamento.diferenca()).isEqualTo(new BigDecimal("0.00"));
        assertThat(fechamento.justificativa()).isNull();
        assertThat(fechamento.caixaId()).isEqualTo(caixa.getId());
        assertThat(fechamento.por()).isEqualTo(RECEPCAO);
        assertThat(fechamento.em()).isEqualTo(Instant.parse("2026-11-03T12:00:00.123456Z"));
        assertThat(caixa.getSituacao()).isEqualTo(Caixa.Situacao.FECHADO);
    }

    @Test
    void diferencaExigeJustificativa() {
        Caixa caixa = aberto("50");

        assertThatThrownBy(() -> fechar(caixa, "140", "100", "0", null))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("-10.00");
        assertThatThrownBy(() -> fechar(caixa, "160", "100", "0", "  "))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("10.00");
        // Recusado: continua aberto.
        assertThat(caixa.getSituacao()).isEqualTo(Caixa.Situacao.ABERTO);

        Fechamento fechamento = fechar(caixa, "140", "100", "0", "  Dei R$ 10 de troco a mais  ");

        assertThat(fechamento.diferenca()).isEqualTo(new BigDecimal("-10.00"));
        assertThat(fechamento.justificativa()).isEqualTo("Dei R$ 10 de troco a mais");
    }

    @Test
    void justificativaOpcionalSemDiferencaEComLimite() {
        assertThat(fechar(aberto("0"), "0", "0", "0", "Conferido com o gerente").justificativa())
                .isEqualTo("Conferido com o gerente");
        assertThat(fechar(aberto("0"), "1", "0", "0", "x".repeat(500)).justificativa()).hasSize(500);
        assertThatThrownBy(() -> fechar(aberto("0"), "1", "0", "0", "x".repeat(501)))
                .isInstanceOf(CobrancaInvalidaException.class);
    }

    /** Um estorno em dinheiro de um pagamento de outro dia pode deixar o esperado negativo. */
    @Test
    void esperadoNegativo() {
        Fechamento fechamento = fechar(aberto("20"), "0", "0", "100", "Estorno do pagamento de ontem");

        assertThat(fechamento.esperado()).isEqualTo(new BigDecimal("-80.00"));
        assertThat(fechamento.diferenca()).isEqualTo(new BigDecimal("80.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "100000000.01", "1.001"})
    void contadoForaDaFaixaRecusado(String contado) {
        Caixa caixa = aberto("0");
        assertThatThrownBy(() -> fechar(caixa, contado, "0", "0", "Teste"))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("contado");
        assertThatThrownBy(() -> caixa.fechar(null, BigDecimal.ZERO, BigDecimal.ZERO, "Teste", RECEPCAO, AGORA))
                .isInstanceOf(CobrancaInvalidaException.class);
        assertThat(fechar(caixa, "100000000", "0", "0", "Teste").contado()).isEqualTo(new BigDecimal("100000000.00"));
    }

    @Test
    void fechadoNaoRecebeNemFechaDeNovo() {
        Caixa caixa = aberto("0");
        fechar(caixa, "0", "0", "0", null);

        assertThatThrownBy(caixa::conferirQueRecebe).isInstanceOf(OperacaoDeCaixaRecusadaException.class)
                .hasMessageContaining("fechado");
        assertThatThrownBy(() -> fechar(caixa, "0", "0", "0", null))
                .isInstanceOf(OperacaoDeCaixaRecusadaException.class).hasMessageContaining("já está fechado");
    }

    @Test
    void reabreComMotivoEVoltaAReceber() {
        Caixa caixa = aberto("0");
        fechar(caixa, "0", "0", "0", null);

        assertThatThrownBy(() -> caixa.reabrir("  ", ADMIN, AGORA)).isInstanceOf(CobrancaInvalidaException.class);
        assertThatThrownBy(() -> caixa.reabrir(null, ADMIN, AGORA)).isInstanceOf(CobrancaInvalidaException.class);
        assertThatThrownBy(() -> caixa.reabrir("x".repeat(501), ADMIN, AGORA))
                .isInstanceOf(CobrancaInvalidaException.class);
        assertThat(caixa.getSituacao()).isEqualTo(Caixa.Situacao.FECHADO);

        Reabertura reabertura = caixa.reabrir("  Aluno chegou depois do fechamento  ", ADMIN, AGORA);

        assertThat(reabertura.motivo()).isEqualTo("Aluno chegou depois do fechamento");
        assertThat(reabertura.caixaId()).isEqualTo(caixa.getId());
        assertThat(reabertura.por()).isEqualTo(ADMIN);
        assertThat(reabertura.em()).isEqualTo(Instant.parse("2026-11-03T12:00:00.123456Z"));
        assertThat(caixa.getSituacao()).isEqualTo(Caixa.Situacao.ABERTO);
        assertThatCode(caixa::conferirQueRecebe).doesNotThrowAnyException();
    }

    @Test
    void abertoNaoReabre() {
        assertThatThrownBy(() -> aberto("0").reabrir("Engano", ADMIN, AGORA))
                .isInstanceOf(OperacaoDeCaixaRecusadaException.class).hasMessageContaining("já está aberto");
    }

    /** A recepção vê hoje e os dias abertos; com o caixa aberto, a contagem é às cegas (decisão do dono). */
    @Test
    void oQueARecepcaoVe() {
        Caixa caixa = aberto("0");
        assertThat(caixa.recepcaoVe(DIA)).isTrue();
        assertThat(caixa.recepcaoVe(DIA.plusDays(1))).as("dia anterior ainda aberto").isTrue();
        assertThat(caixa.contagemAsCegas()).isTrue();

        fechar(caixa, "0", "0", "0", null);
        assertThat(caixa.recepcaoVe(DIA)).as("hoje, já fechado").isTrue();
        assertThat(caixa.recepcaoVe(DIA.plusDays(1))).as("dia passado fechado: só o ADMIN").isFalse();
        assertThat(caixa.recepcaoVe(DIA.minusDays(1))).isFalse();
        assertThat(caixa.contagemAsCegas()).isFalse();

        caixa.reabrir("Recontar", ADMIN, AGORA);
        assertThat(caixa.recepcaoVe(DIA.plusDays(1))).isTrue();
        assertThat(caixa.contagemAsCegas()).isTrue();
    }
}
