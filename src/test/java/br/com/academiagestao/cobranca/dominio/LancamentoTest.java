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
    private static final UUID CAIXA = UUID.randomUUID();
    private static final UUID OUTRO_CAIXA = UUID.randomUUID();

    private static Cobranca emAberto() {
        return Cobranca.nova(UUID.randomUUID(), UUID.randomUUID(),
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, new BigDecimal("100"), VENCIMENTO));
    }

    /** Paga com 15 dias de atraso: 100 + 2,00 de acréscimo + 0,50 de juros. */
    private static Cobranca paga() {
        Cobranca cobranca = emAberto();
        cobranca.pagar(RegrasDeAtraso.PADRAO, VENCIMENTO.plusDays(15), new BigDecimal("102.50"),
                MeioDePagamento.CARTAO_DEBITO, "E2E123", RECEPCAO, Instant.parse("2026-10-25T13:00:00.123456789Z"));
        return cobranca;
    }

    @Test
    void pagamentoLevaAsPartesDoDevido() {
        Cobranca cobranca = paga();

        Lancamento pagamento = Lancamento.doPagamento(cobranca, CAIXA);

        assertThat(pagamento.tipo()).isEqualTo(Lancamento.Tipo.PAGAMENTO);
        assertThat(pagamento.cobrancaId()).isEqualTo(cobranca.getId());
        assertThat(pagamento.estorna()).isNull();
        assertThat(pagamento.motivo()).isNull();
        assertThat(pagamento.valor()).isEqualByComparingTo("100");
        assertThat(pagamento.acrescimo()).isEqualByComparingTo("2.00");
        assertThat(pagamento.juros()).isEqualByComparingTo("0.50");
        assertThat(pagamento.total()).isEqualByComparingTo("102.50");
        assertThat(pagamento.meio()).isEqualTo(MeioDePagamento.CARTAO_DEBITO);
        assertThat(pagamento.codigo()).isEqualTo("E2E123");
        assertThat(pagamento.em()).isEqualTo(cobranca.getPagamento().em());
        assertThat(pagamento.por()).isEqualTo(RECEPCAO);
        assertThat(pagamento.caixaId()).isEqualTo(CAIXA);
    }

    @Test
    void lancamentoDoBalcaoSemCaixaNaoExiste() {
        assertThatThrownBy(() -> Lancamento.doPagamento(paga(), null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caixa do dia");
        Lancamento pagamento = Lancamento.doPagamento(paga(), CAIXA);
        assertThatThrownBy(() -> pagamento.estornar("Engano", ADMIN, Instant.now(), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("caixa do dia");
    }

    /** O online (ADR 0034) é o contrário: o dinheiro entra e sai pela conta, nunca pelo caixa. */
    @Test
    void lancamentoOnlineNuncaTemCaixa() {
        Cobranca cobranca = emAberto();
        cobranca.pagarOnline(new ValorDevido(new BigDecimal("100"), new BigDecimal("2.00"), new BigDecimal("0.50"), 15),
                new BigDecimal("102.50"), MeioDePagamento.BOLETO, RECEPCAO, Instant.parse("2026-10-25T13:00:00Z"));

        assertThatThrownBy(() -> Lancamento.doPagamento(cobranca, CAIXA)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não entra no caixa");
        Lancamento pagamento = Lancamento.doPagamento(cobranca, null);
        assertThat(pagamento.caixaId()).isNull();
        assertThat(pagamento.meio()).isEqualTo(MeioDePagamento.BOLETO);
        assertThat(pagamento.total()).isEqualByComparingTo("102.50");
        assertThatThrownBy(() -> pagamento.estornar("Engano", ADMIN, Instant.now(), CAIXA))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("não entra no caixa");
        assertThat(pagamento.estornar("Engano", ADMIN, Instant.now(), null).caixaId()).isNull();
    }

    @Test
    void soCobrancaPagaTemPagamento() {
        assertThatThrownBy(() -> Lancamento.doPagamento(emAberto(), CAIXA)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void estornoRepeteOsValoresEApontaParaOPagamento() {
        Lancamento pagamento = Lancamento.doPagamento(paga(), CAIXA);
        Instant agora = Instant.parse("2026-10-26T09:00:00.987654321Z");

        Lancamento estorno = pagamento.estornar("  Cartão contestado  ", ADMIN, agora, OUTRO_CAIXA);

        assertThat(estorno.tipo()).isEqualTo(Lancamento.Tipo.ESTORNO);
        assertThat(estorno.id()).isNotEqualTo(pagamento.id());
        assertThat(estorno.estorna()).isEqualTo(pagamento.id());
        assertThat(estorno.cobrancaId()).isEqualTo(pagamento.cobrancaId());
        assertThat(estorno).extracting(Lancamento::valor, Lancamento::acrescimo, Lancamento::juros, Lancamento::total,
                Lancamento::meio, Lancamento::codigo).containsExactly(pagamento.valor(), pagamento.acrescimo(),
                pagamento.juros(), pagamento.total(), pagamento.meio(), "E2E123");
        assertThat(estorno.motivo()).isEqualTo("Cartão contestado");
        assertThat(estorno.por()).isEqualTo(ADMIN);
        // O dinheiro sai do caixa do dia do estorno, não do dia do pagamento (ADR 0031).
        assertThat(estorno.caixaId()).isEqualTo(OUTRO_CAIXA);
        // O banco guarda microssegundos: sem truncar, o lido e o gravado divergiriam.
        assertThat(estorno.em()).isEqualTo(Instant.parse("2026-10-26T09:00:00.987654Z"));
    }

    @Test
    void estornoPedeMotivoEQuemEstornou() {
        Lancamento pagamento = Lancamento.doPagamento(paga(), CAIXA);
        Instant agora = Instant.now();

        assertThatThrownBy(() -> pagamento.estornar(null, ADMIN, agora, OUTRO_CAIXA))
                .isInstanceOf(CobrancaInvalidaException.class);
        assertThatThrownBy(() -> pagamento.estornar("   ", ADMIN, agora, OUTRO_CAIXA))
                .isInstanceOf(CobrancaInvalidaException.class);
        assertThatThrownBy(() -> pagamento.estornar("x".repeat(501), ADMIN, agora, OUTRO_CAIXA))
                .isInstanceOf(CobrancaInvalidaException.class);
        assertThat(pagamento.estornar("x".repeat(500), ADMIN, agora, OUTRO_CAIXA).motivo()).hasSize(500);
        assertThatThrownBy(() -> pagamento.estornar("Engano", null, agora, OUTRO_CAIXA))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void estornoNaoSeEstorna() {
        Lancamento estorno = Lancamento.doPagamento(paga(), CAIXA).estornar("Engano", ADMIN, Instant.now(),
                OUTRO_CAIXA);

        assertThatThrownBy(() -> estorno.estornar("De novo", ADMIN, Instant.now(), OUTRO_CAIXA))
                .isInstanceOf(IllegalStateException.class);
    }
}
