package br.com.academiagestao.avisos;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Os dias dos avisos de fatura (ADR 0047): 5, 3 e 1 antes, no dia, e 1, 3 e 5 de atraso. Só no dia exato. */
class MarcoTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 10);

    @Test
    void cadaMarcoNoSeuDia() {
        assertThat(Marco.de(HOJE.plusDays(5), HOJE)).contains(Marco.VENCE_EM_5);
        assertThat(Marco.de(HOJE.plusDays(3), HOJE)).contains(Marco.VENCE_EM_3);
        assertThat(Marco.de(HOJE.plusDays(1), HOJE)).contains(Marco.VENCE_EM_1);
        assertThat(Marco.de(HOJE, HOJE)).contains(Marco.VENCE_HOJE);
        assertThat(Marco.de(HOJE.minusDays(1), HOJE)).contains(Marco.ATRASADA_1);
        assertThat(Marco.de(HOJE.minusDays(3), HOJE)).contains(Marco.ATRASADA_3);
        assertThat(Marco.de(HOJE.minusDays(5), HOJE)).contains(Marco.ATRASADA_5);
    }

    @Test
    void foraDosMarcosNada() {
        for (int dias : new int[] {6, 4, 2, -2, -4, -6, -10, 30}) {
            assertThat(Marco.de(HOJE.plusDays(dias), HOJE)).as("prazo daqui a %d dias", dias).isEmpty();
        }
    }

    @Test
    void prazoDeCadaMarcoEAtraso() {
        assertThat(Marco.VENCE_EM_5.prazoPara(HOJE)).isEqualTo(HOJE.plusDays(5));
        assertThat(Marco.ATRASADA_3.prazoPara(HOJE)).isEqualTo(HOJE.minusDays(3));
        assertThat(Marco.VENCE_HOJE.atrasada()).isFalse();
        assertThat(Marco.VENCE_EM_1.atrasada()).isFalse();
        assertThat(Marco.ATRASADA_1.atrasada()).isTrue();
        assertThat(Marco.ATRASADA_5.diasDepoisDoPrazo()).isEqualTo(5);
    }
}
