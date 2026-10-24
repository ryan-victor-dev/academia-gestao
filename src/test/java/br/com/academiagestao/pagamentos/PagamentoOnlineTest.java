package br.com.academiagestao.pagamentos;

import br.com.academiagestao.pagamentos.PagamentoOnline.Situacao;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Gerado;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O ciclo de vida de um Pix ou boleto gerado (ADR 0034). */
class PagamentoOnlineTest {

    private static final Instant AGORA = Instant.parse("2026-11-10T15:00:00.123456789Z");
    private static final Instant VALIDO_ATE = Instant.parse("2026-11-11T03:00:00Z");

    private static PagamentoOnline pix() {
        return PagamentoOnline.novo(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Forma.PIX, "simulado",
                new Gerado("sim_1", "copia-e-cola", null, null), new BigDecimal("100"), new BigDecimal("2.00"),
                new BigDecimal("0.50"), VALIDO_ATE, UUID.randomUUID(), AGORA);
    }

    private static PagamentoOnline em(Situacao situacao) {
        PagamentoOnline p = pix();
        return PagamentoOnline.restaurar(p.getId(), p.getCobrancaId(), p.getAlunoId(), p.getForma(), p.getProvedor(),
                p.getIdExterno(), p.getValor(), p.getAcrescimo(), p.getJuros(), p.getPixCopiaECola(), null, null,
                p.getValidoAte(), p.getGeradoEm(), p.getGeradoPor(), situacao,
                situacao == Situacao.A_DEVOLVER ? new BigDecimal("102.50") : null,
                situacao == Situacao.A_DEVOLVER ? AGORA : null, situacao == Situacao.A_DEVOLVER ? "motivo" : null,
                null, null);
    }

    @Test
    void nasceAguardandoComOTotalCombinado() {
        PagamentoOnline p = pix();
        assertThat(p.getSituacao()).isEqualTo(Situacao.AGUARDANDO);
        assertThat(p.total()).isEqualByComparingTo("102.50");
        assertThat(p.getGeradoEm()).isEqualTo(Instant.parse("2026-11-10T15:00:00.123456Z"));
    }

    @Test
    void empresaPrecisaDevolverOQueGerou() {
        assertThatThrownBy(() -> PagamentoOnline.novo(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Forma.BOLETO, "simulado", new Gerado("sim_1", "pix", null, null), BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, VALIDO_ATE, UUID.randomUUID(), AGORA)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PagamentoOnline.novo(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Forma.PIX, "simulado", new Gerado("sim_1", null, "123", null), BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, VALIDO_ATE, UUID.randomUUID(), AGORA)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PagamentoOnline.novo(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Forma.PIX, "simulado", new Gerado(null, "pix", null, null), BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, VALIDO_ATE, UUID.randomUUID(), AGORA)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void valeAteOPrazoSoAguardando() {
        PagamentoOnline p = pix();
        assertThat(p.vale(VALIDO_ATE.minusNanos(1000))).isTrue();
        assertThat(p.vale(VALIDO_ATE)).isFalse();
        p.cancelar();
        assertThat(p.vale(AGORA)).isFalse();
    }

    @Test
    void recebido() {
        PagamentoOnline p = pix();
        p.receber(new BigDecimal("102.50"), AGORA);
        assertThat(p.getSituacao()).isEqualTo(Situacao.PAGO);
        assertThat(p.getRecebido()).isEqualByComparingTo("102.50");
        assertThat(p.getRecebidoEm()).isEqualTo(Instant.parse("2026-11-10T15:00:00.123456Z"));
    }

    /** Expirado ou trocado ainda recebe: o aluno pode ter pago no último segundo. */
    @ParameterizedTest
    @EnumSource(value = Situacao.class, names = {"AGUARDANDO", "EXPIRADO", "CANCELADO"})
    void recebeDepoisDeEncerrado(Situacao situacao) {
        PagamentoOnline p = em(situacao);
        p.aDevolver(new BigDecimal("102.50"), AGORA, "já paga no balcão");
        assertThat(p.getSituacao()).isEqualTo(Situacao.A_DEVOLVER);
        assertThat(p.getMotivoDaDevolucao()).isEqualTo("já paga no balcão");
        PagamentoOnline outro = em(situacao);
        outro.receber(new BigDecimal("102.50"), AGORA);
        assertThat(outro.getSituacao()).isEqualTo(Situacao.PAGO);
    }

    @ParameterizedTest
    @EnumSource(value = Situacao.class, names = {"PAGO", "A_DEVOLVER", "DEVOLVIDO"})
    void naoRecebeDuasVezes(Situacao situacao) {
        assertThatThrownBy(() -> em(situacao).receber(BigDecimal.TEN, AGORA)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> em(situacao).aDevolver(BigDecimal.TEN, AGORA, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Situacao.class, names = {"PAGO", "EXPIRADO", "CANCELADO", "A_DEVOLVER", "DEVOLVIDO"})
    void soAguardandoExpiraOuCancela(Situacao situacao) {
        assertThatThrownBy(() -> em(situacao).expirar()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> em(situacao).cancelar()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void soADevolverSeDevolve() {
        UUID admin = UUID.randomUUID();
        PagamentoOnline p = em(Situacao.A_DEVOLVER);
        p.devolver(admin, AGORA);
        assertThat(p.getSituacao()).isEqualTo(Situacao.DEVOLVIDO);
        assertThat(p.getDevolvidoPor()).isEqualTo(admin);
        assertThat(p.getDevolvidoEm()).isEqualTo(Instant.parse("2026-11-10T15:00:00.123456Z"));
        assertThatThrownBy(() -> p.devolver(admin, AGORA)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pix().devolver(admin, AGORA)).isInstanceOf(IllegalStateException.class);
    }
}
