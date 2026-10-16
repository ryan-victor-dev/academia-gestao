package br.com.academiagestao.alunos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersaoEsperadaTest {

    @ParameterizedTest
    @ValueSource(strings = {"\"3\"", " \"3\" ", "*", "\"1\", \"3\"", "\"3\",\"9\""})
    void aceitaQuandoAlgumEtagBateOuEhCuringa(String ifMatch) {
        assertThatCode(() -> VersaoEsperada.conferir(ifMatch, 3)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2\"", "3", "W/\"3\"", "\"1\", \"2\"", "\"33\""})
    void recusaVersaoDiferenteSemAspasOuFraca(String ifMatch) {
        assertThatThrownBy(() -> VersaoEsperada.conferir(ifMatch, 3))
                .isInstanceOf(AlunoModificadoException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void exigeOCabecalho(String ifMatch) {
        assertThatThrownBy(() -> VersaoEsperada.conferir(ifMatch, 3))
                .isInstanceOf(IfMatchAusenteException.class);
    }

    @Test
    void formatoDoEtag() {
        assertThat(VersaoEsperada.etag(7)).isEqualTo("\"7\"");
    }
}
