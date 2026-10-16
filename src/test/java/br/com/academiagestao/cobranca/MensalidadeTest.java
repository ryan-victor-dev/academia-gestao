package br.com.academiagestao.cobranca;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MensalidadeTest {

    private static final LocalDate VENCIMENTO = LocalDate.of(2026, 11, 10);
    private static final Instant AGORA = Instant.parse("2026-11-05T12:00:00Z");

    private Mensalidade novaMensalidade() {
        return Mensalidade.nova(UUID.randomUUID(), new BigDecimal("129.90"), VENCIMENTO);
    }

    @Nested
    @DisplayName("Criação")
    class Criacao {

        @Test
        @DisplayName("nasce PENDENTE")
        void nasce_pendente() {
            Mensalidade m = novaMensalidade();

            assertThat(m.getStatus()).isEqualTo(StatusMensalidade.PENDENTE);
        }

        @Test
        @DisplayName("não aceita valor zero")
        void nao_aceita_valor_zero() {
            assertThatThrownBy(() -> Mensalidade.nova(UUID.randomUUID(), BigDecimal.ZERO, VENCIMENTO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Pagamento")
    class Pagamento {

        @Test
        @DisplayName("pendente pode ser paga")
        void pendente_pode_ser_paga() {
            // Arrange (preparar)
            Mensalidade m = novaMensalidade();

            // Act (agir)
            m.pagar(AGORA);

            // Assert (conferir)
            assertThat(m.getStatus()).isEqualTo(StatusMensalidade.PAGA);
            assertThat(m.getPagaEm()).isEqualTo(AGORA);
        }

        @Test
        @DisplayName("vencida ainda pode ser paga")
        void vencida_pode_ser_paga() {
            Mensalidade m = novaMensalidade();
            m.marcarVencida(VENCIMENTO.plusDays(1));

            m.pagar(AGORA);

            assertThat(m.getStatus()).isEqualTo(StatusMensalidade.PAGA);
        }

        @Test
        @DisplayName("pagar duas vezes é erro (protege contra webhook duplicado)")
        void nao_paga_duas_vezes() {
            Mensalidade m = novaMensalidade();
            m.pagar(AGORA);

            assertThatThrownBy(() -> m.pagar(AGORA))
                    .isInstanceOf(TransicaoInvalidaException.class)
                    .hasMessageContaining("PAGA -> PAGA");
        }
    }

    @Nested
    @DisplayName("Vencimento")
    class Vencimento {

        @Test
        @DisplayName("não vence no próprio dia do vencimento")
        void nao_vence_no_dia() {
            Mensalidade m = novaMensalidade();

            assertThatThrownBy(() -> m.marcarVencida(VENCIMENTO))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(m.getStatus()).isEqualTo(StatusMensalidade.PENDENTE);
        }

        @Test
        @DisplayName("vence no dia seguinte")
        void vence_no_dia_seguinte() {
            Mensalidade m = novaMensalidade();

            m.marcarVencida(VENCIMENTO.plusDays(1));

            assertThat(m.getStatus()).isEqualTo(StatusMensalidade.VENCIDA);
        }
    }

    @Nested
    @DisplayName("Estorno e cancelamento")
    class EstornoECancelamento {

        @Test
        @DisplayName("só estorna o que foi pago")
        void so_estorna_o_que_foi_pago() {
            Mensalidade m = novaMensalidade();
            assertThatThrownBy(m::estornar).isInstanceOf(TransicaoInvalidaException.class);

            m.pagar(AGORA);
            m.estornar();

            assertThat(m.getStatus()).isEqualTo(StatusMensalidade.ESTORNADA);
        }

        @Test
        @DisplayName("mensalidade paga não pode ser cancelada")
        void paga_nao_pode_ser_cancelada() {
            Mensalidade m = novaMensalidade();
            m.pagar(AGORA);

            assertThatThrownBy(m::cancelar).isInstanceOf(TransicaoInvalidaException.class);
        }
    }

    @ParameterizedTest(name = "{0} é um estado final")
    @EnumSource(value = StatusMensalidade.class, names = {"ESTORNADA", "CANCELADA"})
    void estados_finais_nao_tem_saida(StatusMensalidade status) {
        assertThat(status.isFinal()).isTrue();
        for (StatusMensalidade destino : StatusMensalidade.values()) {
            assertThat(status.podeIrPara(destino)).isFalse();
        }
    }
}