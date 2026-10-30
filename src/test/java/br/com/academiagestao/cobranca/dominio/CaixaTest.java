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
        return Caixa.abrir(DIA, new BigDecimal(troco), null, RECEPCAO, AGORA);
    }

    private static Fechamento fechar(Caixa caixa, String contado, String recebido, String estornado, String justificativa) {
        return caixa.fechar(new BigDecimal(contado), null, new BigDecimal(recebido), new BigDecimal(estornado),
                justificativa, RECEPCAO, AGORA);
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
        assertThatThrownBy(() -> Caixa.abrir(DIA, null, null, RECEPCAO, AGORA)).isInstanceOf(CobrancaInvalidaException.class);
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

        // A mensagem não diz a diferença nem o esperado: a contagem é às cegas (diagnóstico M2).
        assertThatThrownBy(() -> fechar(caixa, "140", "100", "0", null))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("justificativa")
                .hasMessageNotContaining("10").hasMessageNotContaining("150");
        assertThatThrownBy(() -> fechar(caixa, "160", "100", "0", "  "))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageNotContaining("10")
                .hasMessageNotContaining("150");
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
        assertThatThrownBy(() -> caixa.fechar(null, null, BigDecimal.ZERO, BigDecimal.ZERO, "Teste", RECEPCAO, AGORA))
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

    /**
     * Valor com centavos exatos é aceito no troco e no contado; um terço de centavo, não
     * (pendências baixas, item 27: os testes só usavam valores redondos).
     */
    @Test
    void centavosExatosNoTrocoENoContado() {
        Caixa caixa = aberto("10.55");
        assertThat(caixa.getTroco()).isEqualByComparingTo("10.55");
        assertThatThrownBy(() -> aberto("10.555")).isInstanceOf(CobrancaInvalidaException.class);

        assertThatThrownBy(() -> fechar(aberto("10.55"), "10.555", "0", "0", null))
                .isInstanceOf(CobrancaInvalidaException.class);
        assertThatCode(() -> fechar(caixa, "10.55", "0", "0", null)).doesNotThrowAnyException();
    }

    @Test
    void motivoDaReaberturaComOTamanhoMaximo() {
        Caixa caixa = aberto("0");
        fechar(caixa, "0", "0", "0", null);

        assertThat(caixa.reabrir("x".repeat(500), ADMIN, AGORA).motivo()).hasSize(500);
    }

    @Test
    void restauraOQueFoiLidoDoBanco() {
        UUID id = UUID.randomUUID();
        Caixa caixa = Caixa.restaurar(id, DIA, new BigDecimal("50.00"), new BigDecimal("80.00"), Caixa.Situacao.FECHADO,
                AGORA, RECEPCAO);

        assertThat(caixa.getId()).isEqualTo(id);
        assertThat(caixa.getDia()).isEqualTo(DIA);
        assertThat(caixa.getTroco()).isEqualByComparingTo("50.00");
        assertThat(caixa.getTrocoEsperado()).isEqualByComparingTo("80.00");
        assertThat(caixa.getSituacao()).isEqualTo(Caixa.Situacao.FECHADO);
        assertThat(caixa.getAbertoEm()).isEqualTo(AGORA);
        assertThat(caixa.getAbertoPor()).isEqualTo(RECEPCAO);
    }

    /** Item 8 (ADR 0046): o troco é comparado com o que ficou na gaveta no fechamento anterior. */
    @Test
    void diferencaNaAberturaEOTrocoMenosOQueFicou() {
        Caixa menor = Caixa.abrir(DIA, new BigDecimal("50"), new BigDecimal("80"), RECEPCAO, AGORA);
        assertThat(menor.getTrocoEsperado()).isEqualTo(new BigDecimal("80.00"));
        assertThat(menor.diferencaNaAbertura()).isEqualTo(new BigDecimal("-30.00"));
        // Aceita mesmo diferente: a abertura é às cegas, quem compara é o ADMIN.
        assertThatCode(menor::conferirQueRecebe).doesNotThrowAnyException();

        assertThat(Caixa.abrir(DIA, new BigDecimal("80"), new BigDecimal("80"), RECEPCAO, AGORA).diferencaNaAbertura())
                .isEqualTo(new BigDecimal("0.00"));
        assertThat(Caixa.abrir(DIA, new BigDecimal("90.5"), new BigDecimal("80"), RECEPCAO, AGORA).diferencaNaAbertura())
                .isEqualTo(new BigDecimal("10.50"));
        Caixa primeiro = aberto("50");
        assertThat(primeiro.getTrocoEsperado()).isNull();
        assertThat(primeiro.diferencaNaAbertura()).isNull();
    }

    @Test
    void fechamentoGuardaORetirado() {
        Caixa caixa = aberto("50");
        Fechamento fechamento = caixa.fechar(new BigDecimal("150"), new BigDecimal("100.5"), new BigDecimal("100"),
                BigDecimal.ZERO, null, RECEPCAO, AGORA);

        assertThat(fechamento.retirado()).isEqualTo(new BigDecimal("100.50"));
        // O retirado sai depois de contar: não muda o esperado nem a diferença.
        assertThat(fechamento.esperado()).isEqualTo(new BigDecimal("150.00"));
        assertThat(fechamento.diferenca()).isEqualTo(new BigDecimal("0.00"));
        assertThat(fechar(aberto("0"), "10", "10", "0", null).retirado()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void retiradoNasPontasDaFaixa() {
        assertThat(aberto("0").fechar(new BigDecimal("40"), new BigDecimal("40"), new BigDecimal("40"), BigDecimal.ZERO,
                null, RECEPCAO, AGORA).retirado()).isEqualTo(new BigDecimal("40.00"));
        assertThat(aberto("0").fechar(new BigDecimal("40"), BigDecimal.ZERO, new BigDecimal("40"), BigDecimal.ZERO,
                null, RECEPCAO, AGORA).retirado()).isEqualTo(new BigDecimal("0.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "40.01", "1.001"})
    void retiradoForaDaFaixaRecusado(String retirado) {
        Caixa caixa = aberto("0");
        assertThatThrownBy(() -> caixa.fechar(new BigDecimal("40"), new BigDecimal(retirado), new BigDecimal("40"),
                BigDecimal.ZERO, null, RECEPCAO, AGORA))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("retirado");
        assertThat(caixa.getSituacao()).isEqualTo(Caixa.Situacao.ABERTO);
    }
}
