package br.com.academiagestao.compartilhado;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A aplicação não sobe com senha fraca para o usuário do banco, nem com um nome que vire SQL. */
class UsuarioDaAplicacaoNoBancoValidacaoTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"curta", "quinze-letras15"})
    void recusaSenhaVaziaOuCurta(String senha) {
        assertThatThrownBy(() -> new UsuarioDaAplicacaoNoBanco("academia_app", senha))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_APP_SENHA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"academia_app; DROP TABLE alunos", "Academia", "app-x", "1app", ""})
    void recusaNomeQueNaoSejaUmIdentificadorSimples(String nome) {
        assertThatThrownBy(() -> new UsuarioDaAplicacaoNoBanco(nome, "senha-bem-comprida-123"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aceitaNomeESenhaValidos() {
        assertThatCode(() -> new UsuarioDaAplicacaoNoBanco("academia_app", "dezesseis-letras"))
                .doesNotThrowAnyException();
    }
}
