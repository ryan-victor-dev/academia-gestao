package br.com.academiagestao.compartilhado;

import br.com.academiagestao.compartilhado.Identidade.IdentidadeDeGenero;
import br.com.academiagestao.compartilhado.Identidade.OrientacaoSexual;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As regras da descrição da opção "outra" (ADR 0047, decisão 2). */
class IdentidadeTest {

    @Test
    void tudoNuloEhNaoRespondida() {
        assertThat(new Identidade(null, null, null, null)).isEqualTo(Identidade.NAO_RESPONDIDA);
    }

    @Test
    void outraExigeDescricaoSemOsEspacosDasPontas() {
        Identidade identidade = new Identidade(IdentidadeDeGenero.OUTRA, "  bigênero ", OrientacaoSexual.OUTRA, "\tqueer e arromântica ");

        assertThat(identidade.identidadeDeGeneroOutra()).isEqualTo("bigênero");
        assertThat(identidade.orientacaoSexualOutra()).isEqualTo("queer e arromântica");
    }

    @Test
    void outraSemDescricaoEhRecusada() {
        assertThatThrownBy(() -> new Identidade(IdentidadeDeGenero.OUTRA, null, null, null))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("identidadeDeGeneroOutra");
        assertThatThrownBy(() -> new Identidade(null, null, OrientacaoSexual.OUTRA, "   "))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("orientacaoSexualOutra");
    }

    @Test
    void descricaoSemOutraEhRecusadaEEmBrancoContaComoAusente() {
        assertThatThrownBy(() -> new Identidade(IdentidadeDeGenero.MULHER_TRANS, "texto", null, null))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("só acompanha");
        assertThatThrownBy(() -> new Identidade(null, "texto", null, null))
                .isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> new Identidade(null, null, OrientacaoSexual.PREFIRO_NAO_INFORMAR, "x"))
                .isInstanceOf(DadosInvalidosException.class);

        Identidade emBranco = new Identidade(IdentidadeDeGenero.NAO_BINARIA, " ", OrientacaoSexual.BISSEXUAL, "");
        assertThat(emBranco.identidadeDeGeneroOutra()).isNull();
        assertThat(emBranco.orientacaoSexualOutra()).isNull();
    }

    @Test
    void descricaoVaiAte50Caracteres() {
        String cinquenta = "a".repeat(50);
        assertThat(new Identidade(IdentidadeDeGenero.OUTRA, " " + cinquenta + " ", null, null).identidadeDeGeneroOutra())
                .isEqualTo(cinquenta);
        assertThatThrownBy(() -> new Identidade(null, null, OrientacaoSexual.OUTRA, cinquenta + "a"))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("50");
    }
}
