package br.com.academiagestao.cobranca.dominio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O ciclo de vida da cobrança (ADRs 0004 e 0024). */
class CobrancaTest {

    private static final LocalDate VENCIMENTO = LocalDate.of(2026, 11, 10);
    private static final Instant AGORA = Instant.parse("2026-11-12T15:00:00.123456789Z");
    private static final UUID RECEPCAO = UUID.randomUUID();

    private static Cobranca cobranca() {
        return RegrasDeAtrasoTest.cobranca("100");
    }

    private static void pagarEmDia(Cobranca cobranca) {
        cobranca.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO, new BigDecimal("100"), MeioDePagamento.DINHEIRO,
                RECEPCAO, AGORA);
    }

    @Test
    void nasceEmAbertoComOsDadosDaParcela() {
        UUID matricula = UUID.randomUUID();
        UUID aluno = UUID.randomUUID();
        Cobranca cobranca = Cobranca.nova(matricula, aluno,
                new ParcelaPrevista(TipoCobranca.A_VISTA, 1, new BigDecimal("1200"), VENCIMENTO));

        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PENDENTE);
        assertThat(cobranca.getId()).isNotNull();
        assertThat(cobranca.getMatriculaId()).isEqualTo(matricula);
        assertThat(cobranca.getAlunoId()).isEqualTo(aluno);
        assertThat(cobranca.getTipo()).isEqualTo(TipoCobranca.A_VISTA);
        assertThat(cobranca.getParcela()).isEqualTo(1);
        assertThat(cobranca.getValor()).hasToString("1200.00");
        assertThat(cobranca.getVencimento()).isEqualTo(VENCIMENTO);
        assertThat(cobranca.getPagamento()).isNull();
        assertThat(cobranca.getCancelamento()).isNull();
        assertThat(cobranca.getVersao()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void valorPrecisaSerPositivo(String valor) {
        assertThatThrownBy(() -> RegrasDeAtrasoTest.cobranca(valor)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cobranca.nova(UUID.randomUUID(), UUID.randomUUID(),
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, null, VENCIMENTO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dadosObrigatorios() {
        ParcelaPrevista parcela = new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, BigDecimal.TEN, VENCIMENTO);
        assertThatThrownBy(() -> Cobranca.nova(null, UUID.randomUUID(), parcela)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Cobranca.nova(UUID.randomUUID(), null, parcela)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Cobranca.nova(UUID.randomUUID(), UUID.randomUUID(),
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, BigDecimal.TEN, null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pagaEmDiaPeloValor() {
        Cobranca cobranca = cobranca();

        cobranca.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO, new BigDecimal("100.00"), MeioDePagamento.PIX,
                RECEPCAO, AGORA);

        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PAGA);
        Pagamento pagamento = cobranca.getPagamento();
        assertThat(pagamento.total()).isEqualByComparingTo("100");
        assertThat(pagamento.acrescimo()).isEqualByComparingTo("0");
        assertThat(pagamento.juros()).isEqualByComparingTo("0");
        assertThat(pagamento.meio()).isEqualTo(MeioDePagamento.PIX);
        assertThat(pagamento.por()).isEqualTo(RECEPCAO);
        // O Postgres guarda microssegundos.
        assertThat(pagamento.em()).isEqualTo(Instant.parse("2026-11-12T15:00:00.123456Z"));
    }

    @Test
    void atrasadaPagaComAcrescimoEJuros() {
        Cobranca cobranca = cobranca();

        cobranca.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO.plusDays(30), new BigDecimal("103.00"),
                MeioDePagamento.CARTAO_DEBITO, RECEPCAO, AGORA);

        assertThat(cobranca.getPagamento().acrescimo()).isEqualByComparingTo("2.00");
        assertThat(cobranca.getPagamento().juros()).isEqualByComparingTo("1.00");
        assertThat(cobranca.getPagamento().total()).isEqualByComparingTo("103.00");
    }

    /** Só o valor exato (decisão do dono): nem um centavo a menos, nem a mais. */
    @ParameterizedTest
    @ValueSource(strings = {"102.99", "103.01", "100", "0"})
    void valorDiferenteDoDevidoEhRecusadoComODevido(String informado) {
        Cobranca cobranca = cobranca();

        assertThatThrownBy(() -> cobranca.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO.plusDays(30),
                new BigDecimal(informado), MeioDePagamento.PIX, RECEPCAO, AGORA))
                .isInstanceOfSatisfying(PagamentoRecusadoException.class,
                        e -> assertThat(e.getDevido().total()).isEqualByComparingTo("103.00"))
                .hasMessageContaining("103.00");
        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PENDENTE);
        assertThat(cobranca.getPagamento()).isNull();
    }

    @Test
    void valorAusenteEhRecusado() {
        assertThatThrownBy(() -> cobranca().pagar(RegrasDeAtraso.PADRAO, VENCIMENTO, null, MeioDePagamento.PIX,
                RECEPCAO, AGORA)).isInstanceOf(PagamentoRecusadoException.class);
    }

    @Test
    void meioEQuemRegistrouSaoObrigatorios() {
        Cobranca cobranca = cobranca();
        assertThatThrownBy(() -> cobranca.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO, BigDecimal.valueOf(100), null,
                RECEPCAO, AGORA)).isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("meio");
        assertThatThrownBy(() -> cobranca.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO, BigDecimal.valueOf(100),
                MeioDePagamento.PIX, null, AGORA)).isInstanceOf(NullPointerException.class);
        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PENDENTE);
    }

    @Test
    void naoPagaDuasVezes() {
        Cobranca cobranca = cobranca();
        pagarEmDia(cobranca);

        assertThatThrownBy(() -> pagarEmDia(cobranca))
                .isInstanceOfSatisfying(TransicaoInvalidaException.class, e -> {
                    assertThat(e.getOrigem()).isEqualTo(StatusCobranca.PAGA);
                    assertThat(e.getDestino()).isEqualTo(StatusCobranca.PAGA);
                });
    }

    /** Estorno desfaz o pagamento: volta a dever e pode atrasar (decisão do dono). */
    @Test
    void estornoVoltaADever() {
        Cobranca cobranca = cobranca();
        pagarEmDia(cobranca);

        cobranca.estornar();

        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PENDENTE);
        assertThat(cobranca.getPagamento()).isNull();
        assertThat(cobranca.atrasada(VENCIMENTO.plusDays(1))).isTrue();
        pagarEmDia(cobranca);
        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PAGA);
    }

    @Test
    void soEstornaOQueFoiPago() {
        Cobranca cobranca = cobranca();
        assertThatThrownBy(cobranca::estornar).isInstanceOf(TransicaoInvalidaException.class);
        cobranca.cancelar("Engano", RECEPCAO, AGORA);
        assertThatThrownBy(cobranca::estornar).isInstanceOf(TransicaoInvalidaException.class);
    }

    @Test
    void cancelaEmAbertoComMotivo() {
        Cobranca cobranca = cobranca();

        cobranca.cancelar("  Lançada por engano  ", RECEPCAO, AGORA);

        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.CANCELADA);
        assertThat(cobranca.getCancelamento().motivo()).isEqualTo("Lançada por engano");
        assertThat(cobranca.getCancelamento().por()).isEqualTo(RECEPCAO);
        assertThat(cobranca.getCancelamento().em()).isEqualTo(Instant.parse("2026-11-12T15:00:00.123456Z"));
        assertThat(cobranca.atrasada(VENCIMENTO.plusDays(30))).isFalse();
    }

    @Test
    void oSistemaCancelaSemAutor() {
        Cobranca cobranca = cobranca();
        cobranca.cancelar(Cronograma.MOTIVO_DO_ENCERRAMENTO, null, AGORA);
        assertThat(cobranca.getCancelamento().por()).isNull();
    }

    @Test
    void pagaNaoSeCancelaSemEstornarAntes() {
        Cobranca cobranca = cobranca();
        pagarEmDia(cobranca);
        assertThatThrownBy(() -> cobranca.cancelar("Desistiu", RECEPCAO, AGORA))
                .isInstanceOf(TransicaoInvalidaException.class);
        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PAGA);
    }

    @Test
    void canceladaEhFinal() {
        Cobranca cobranca = cobranca();
        cobranca.cancelar("Engano", RECEPCAO, AGORA);
        assertThatThrownBy(() -> cobranca.cancelar("De novo", RECEPCAO, AGORA))
                .isInstanceOf(TransicaoInvalidaException.class);
        assertThatThrownBy(() -> pagarEmDia(cobranca)).isInstanceOf(TransicaoInvalidaException.class);
        assertThatThrownBy(() -> cobranca.reprogramar(VENCIMENTO.plusDays(1)))
                .isInstanceOf(TransicaoInvalidaException.class);
        assertThat(cobranca.getVencimento()).isEqualTo(VENCIMENTO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void motivoObrigatorio(String motivo) {
        Cobranca cobranca = cobranca();
        assertThatThrownBy(() -> cobranca.cancelar(motivo, RECEPCAO, AGORA))
                .isInstanceOf(CobrancaInvalidaException.class).hasMessageContaining("motivo");
        assertThatThrownBy(() -> cobranca.cancelar(null, RECEPCAO, AGORA))
                .isInstanceOf(CobrancaInvalidaException.class);
        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PENDENTE);
    }

    @Test
    void motivoDeAte500Caracteres() {
        cobranca().cancelar("a".repeat(500), RECEPCAO, AGORA);
        assertThatThrownBy(() -> cobranca().cancelar("a".repeat(501), RECEPCAO, AGORA))
                .isInstanceOf(CobrancaInvalidaException.class);
    }

    @Test
    void reprogramaEmAbertoEPaga() {
        Cobranca emAberto = cobranca();
        emAberto.reprogramar(VENCIMENTO.plusDays(30));
        assertThat(emAberto.getVencimento()).isEqualTo(VENCIMENTO.plusDays(30));

        Cobranca paga = cobranca();
        pagarEmDia(paga);
        paga.reprogramar(VENCIMENTO.plusDays(30));
        assertThat(paga.getVencimento()).isEqualTo(VENCIMENTO.plusDays(30));
    }

    @Test
    void atrasadaDoDiaSeguinteEmDiante() {
        Cobranca cobranca = cobranca();
        assertThat(cobranca.atrasada(VENCIMENTO)).isFalse();
        assertThat(cobranca.atrasada(VENCIMENTO.plusDays(1))).isTrue();
        pagarEmDia(cobranca);
        assertThat(cobranca.atrasada(VENCIMENTO.plusDays(1))).isFalse();
    }

    @Test
    void restauraDoBanco() {
        UUID id = UUID.randomUUID();
        Pagamento pagamento = new Pagamento(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, MeioDePagamento.PIX,
                AGORA, RECEPCAO);
        Cobranca cobranca = Cobranca.restaurar(id, UUID.randomUUID(), UUID.randomUUID(), TipoCobranca.MULTA, 1,
                BigDecimal.TEN, VENCIMENTO, StatusCobranca.PAGA, pagamento, null, 7);
        assertThat(cobranca.getId()).isEqualTo(id);
        assertThat(cobranca.getPagamento()).isEqualTo(pagamento);
        assertThat(cobranca.getVersao()).isEqualTo(7);
        cobranca.estornar();
        assertThat(cobranca.getStatus()).isEqualTo(StatusCobranca.PENDENTE);
    }

    @ParameterizedTest
    @EnumSource(StatusCobranca.class)
    void transicoesPermitidas(StatusCobranca status) {
        var esperado = switch (status) {
            case PENDENTE -> java.util.Set.of(StatusCobranca.PAGA, StatusCobranca.CANCELADA);
            case PAGA -> java.util.Set.of(StatusCobranca.PENDENTE);
            case CANCELADA -> java.util.Set.<StatusCobranca>of();
        };
        assertThat(status.proximosPermitidos()).containsExactlyInAnyOrderElementsOf(esperado);
        for (StatusCobranca destino : StatusCobranca.values()) {
            assertThat(status.podeIrPara(destino)).isEqualTo(esperado.contains(destino));
        }
    }
}
