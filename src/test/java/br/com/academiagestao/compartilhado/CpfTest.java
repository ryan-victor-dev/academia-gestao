package br.com.academiagestao.compartilhado;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CpfTest {

    @ParameterizedTest
    @ValueSource(strings = {"52998224725", "529.982.247-25", " 529.982.247-25 ", "11144477735", "12345678909"})
    void aceitaCpfValidoComOuSemPontuacao(String cpf) {
        assertThat(Cpf.ehValido(cpf)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "52998224724",    // último dígito verificador errado
            "52998224715",    // primeiro dígito verificador errado (e, por consequência, o segundo)
            "52998224733",    // só o primeiro errado: o segundo foi calculado sobre o primeiro errado
            "11111111111",    // todos iguais: a conta fecha, mas o CPF não existe
            "00000000000",
            "5299822472",     // 10 dígitos
            "529982247250",   // 12 dígitos
            "5299822472a"     // letra
    })
    @NullAndEmptySource
    void recusaCpfInvalido(String cpf) {
        assertThat(Cpf.ehValido(cpf)).isFalse();
    }

    @Test
    void normalizaEFormata() {
        assertThat(Cpf.normalizar("529.982.247-25")).isEqualTo("52998224725");
        assertThat(Cpf.formatar("52998224725")).isEqualTo("529.982.247-25");
    }
}
