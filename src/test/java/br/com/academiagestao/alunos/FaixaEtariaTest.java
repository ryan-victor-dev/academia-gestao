package br.com.academiagestao.alunos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FaixaEtariaTest {

    @Test
    void osLimitesDeCadaFaixa() {
        assertThat(FaixaEtaria.de(0)).isEqualTo(FaixaEtaria.ATE_17);
        assertThat(FaixaEtaria.de(17)).isEqualTo(FaixaEtaria.ATE_17);
        assertThat(FaixaEtaria.de(18)).isEqualTo(FaixaEtaria.DE_18_A_29);
        assertThat(FaixaEtaria.de(29)).isEqualTo(FaixaEtaria.DE_18_A_29);
        assertThat(FaixaEtaria.de(30)).isEqualTo(FaixaEtaria.DE_30_A_39);
        assertThat(FaixaEtaria.de(39)).isEqualTo(FaixaEtaria.DE_30_A_39);
        assertThat(FaixaEtaria.de(40)).isEqualTo(FaixaEtaria.DE_40_A_49);
        assertThat(FaixaEtaria.de(49)).isEqualTo(FaixaEtaria.DE_40_A_49);
        assertThat(FaixaEtaria.de(50)).isEqualTo(FaixaEtaria.DE_50_A_59);
        assertThat(FaixaEtaria.de(59)).isEqualTo(FaixaEtaria.DE_50_A_59);
        assertThat(FaixaEtaria.de(60)).isEqualTo(FaixaEtaria.A_PARTIR_DE_60);
        assertThat(FaixaEtaria.de(120)).isEqualTo(FaixaEtaria.A_PARTIR_DE_60);
        assertThat(FaixaEtaria.de(null)).isEqualTo(FaixaEtaria.SEM_DATA);
    }
}
