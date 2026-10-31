package br.com.academiagestao.folha;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemLoginTest {

    private static final String CPF = "529.982.247-25";

    @Test
    void limpaONomeEOCpf() {
        SemLogin dados = new SemLogin("  Rosa da Limpeza ", CPF);
        assertThat(dados.nome()).isEqualTo("Rosa da Limpeza");
        assertThat(dados.cpf()).isEqualTo("52998224725");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULO", value = {
            "NULO, " + "nome: é obrigatório",
            "'   ', nome: é obrigatório",
    })
    void nomeObrigatorio(String nome, String mensagem) {
        assertThatThrownBy(() -> new SemLogin(nome, CPF)).isInstanceOf(DadosInvalidosException.class).hasMessage(mensagem);
    }

    @Test
    void nomeAte150() {
        assertThat(new SemLogin("a".repeat(150), CPF).nome()).hasSize(150);
        assertThatThrownBy(() -> new SemLogin("a".repeat(151), CPF))
                .isInstanceOf(DadosInvalidosException.class).hasMessage("nome: pode ter no máximo 150 caracteres");
    }

    @Test
    void nomeNumaLinha() {
        assertThatThrownBy(() -> new SemLogin("Rosa\nda Limpeza", CPF))
                .isInstanceOf(DadosInvalidosException.class).hasMessage("nome: não pode ter quebra de linha");
        assertThatThrownBy(() -> new SemLogin("Rosa\rda Limpeza", CPF))
                .isInstanceOf(DadosInvalidosException.class).hasMessage("nome: não pode ter quebra de linha");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULO", value = {
            "NULO, cpf: é obrigatório",
            "' ', cpf: é obrigatório",
            "529.982.247-24, cpf: inválido",
            "111.111.111-11, cpf: inválido",
    })
    void cpf(String cpf, String mensagem) {
        assertThatThrownBy(() -> new SemLogin("Rosa", cpf)).isInstanceOf(DadosInvalidosException.class).hasMessage(mensagem);
    }
}
