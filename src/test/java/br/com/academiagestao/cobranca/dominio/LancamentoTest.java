package br.com.academiagestao.cobranca.dominio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O registro de pagamentos (ADR 0025): o estorno é um lançamento novo, com os mesmos valores. */
class LancamentoTest {

    private static final LocalDate VENCIMENTO = LocalDate.of(2026, 10, 10);
    private static final UUID RECEPCAO = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    private static Cobranca emAberto() {
        return Cobranca.nova(UUID.randomUUID(), UUID.randomUUID(),
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, new BigDecimal("100"), VENCIMENTO));
    }

    /** Paga com 15 dias de atraso: 100 + 2,00 de acréscimo + 0,50 de juros. */
    private static Cobranca paga() {
        Cobranca cobranca = emAberto();
        cobranca.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO.plusDays(15), new BigDecimal("102.50"),
                MeioDePagamento.CARTAO_DEBITO, RECEPCAO, Instant.parse("2026-10-25T13:00:00.123456789Z"));
        return cobranca;
    }

    @Test
    void pagamentoLevaAsPartesDoDevido() {
        Cobranca cobranca = paga();

        Lancamento pagamento = Lancamento.doPagamento(cobranca);

        assertThat(pagamento.tipo()).isEqualTo(Lancamento.Tipo.PAGAMENTO);
        assertThat(pagamento.cobrancaId()).isEqualTo(cobranca.getId());
        assertThat(pagamento.estorna()).isNull();
        assertThat(pagamento.motivo()).isNull();
        assertThat(pagamento.valor()).isEqualByComparingTo("100");
        assertThat(pagamento.acrescimo()).isEqualByComparingTo("2.00");
        assertThat(pagamento.juros()).isEqualByComparingTo("0.50");
        assertThat(pagamento.total()).isEqualByComparingTo("102.50");
        assertThat(pagamento.meio()).isEqualTo(MeioDePagamento.CARTAO_DEBITO);
        assertThat(pagamento.em()).isEqualTo(cobranca.getPagamento().em());
        assertThat(pagamento.por()).isEqualTo(RECEPCAO);
    }

    @Test
    void soCobrancaPagaTemPagamento() {
        assertThatThrownBy(() -> Lancamento.doPagamento(emAberto())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void estornoRepeteOsValoresEApontaParaOPagamento() {
        Lancamento pagamento = Lancamento.doPagamento(paga());
        Instant agora = Instant.parse("2026-10-26T09:00:00.987654321Z");

        Lancamento estorno = pagamento.estornar("  Cartão contestado  ", ADMIN, agora);

        assertThat(estorno.tipo()).isEqualTo(Lancamento.Tipo.ESTORNO);
        assertThat(estorno.id()).isNotEqualTo(pagamento.id());
        assertThat(estorno.estorna()).isEqualTo(pagamento.id());
        assertThat(estorno.cobrancaId()).isEqualTo(pagamento.cobrancaId());
        assertThat(estorno).extracting(Lancamento::valor, Lancamento::acrescimo, Lancamento::juros, Lancamento::total,
                Lancamento::meio).containsExactly(pagamento.valor(), pagamento.acrescimo(), pagamento.juros(),
                pagamento.total(), pagamento.meio());
        assertThat(estorno.motivo()).isEqualTo("Cartão contestado");
        assertThat(estorno.por()).isEqualTo(ADMIN);
        // O banco guarda microssegundos: sem truncar, o lido e o gravado divergiriam.
        assertThat(estorno.em()).isEqualTo(Instant.parse("2026-10-26T09:00:00.987654Z"));
    }

    @Test
    void estornoPedeMotivoEQuemEstornou() {
        Lancamento pagamento = Lancamento.doPagamento(paga());
        Instant agora = Instant.now();

        assertThatThrownBy(() -> pagamento.estornar(null, ADMIN, agora)).isInstanceOf(CobrancaInvalidaException.class);
        assertThatThrownBy(() -> pagamento.estornar("   ", ADMIN, agora)).isInstanceOf(CobrancaInvalidaException.class);
        assertThatThrownBy(() -> pagamento.estornar("x".repeat(501), ADMIN, agora))
                .isInstanceOf(CobrancaInvalidaException.class);
        assertThat(pagamento.estornar("x".repeat(500), ADMIN, agora).motivo()).hasSize(500);
        assertThatThrownBy(() -> pagamento.estornar("Engano", null, agora)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void estornoNaoSeEstorna() {
        Lancamento estorno = Lancamento.doPagamento(paga()).estornar("Engano", ADMIN, Instant.now());

        assertThatThrownBy(() -> estorno.estornar("De novo", ADMIN, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }
}
