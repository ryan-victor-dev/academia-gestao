package br.com.academiagestao.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsuarioTest {

    private static final String HASH = "hash-qualquer";

    @Test
    void usuarioDaEquipeNasceAtivoComEmailNormalizado() {
        Usuario u = Usuario.daEquipe("Ana", "  Ana@Academia.COM ", HASH, Perfil.RECEPCAO);

        assertThat(u.getEmail()).isEqualTo("ana@academia.com");
        assertThat(u.isAtivo()).isTrue();
        assertThat(u.getAlunoId()).isNull();
        assertThat(u.getId()).isNotNull();
    }

    @Test
    void naoCriaPerfilAlunoPeloCaminhoDaEquipe() {
        assertThatThrownBy(() -> Usuario.daEquipe("Bia", "bia@x.com", HASH, Perfil.ALUNO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alunoPrecisaDoIdDoCadastro() {
        assertThatThrownBy(() -> Usuario.deAluno("Caio", "caio@x.com", HASH, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void alunoFicaLigadoAoCadastro() {
        UUID alunoId = UUID.randomUUID();
        Usuario u = Usuario.deAluno("Caio", "caio@x.com", HASH, alunoId);

        assertThat(u.getPerfil()).isEqualTo(Perfil.ALUNO);
        assertThat(u.getAlunoId()).isEqualTo(alunoId);
    }

    @Test
    void recusaEmailInvalido() {
        assertThatThrownBy(() -> Usuario.daEquipe("Ana", "sem-arroba", HASH, Perfil.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trocarSenhaGravaNovoHashELiberaOUso() {
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        u.exigirTrocaDeSenha();
        assertThat(u.isPrecisaTrocarSenha()).isTrue();

        u.trocarSenha("hash-novo");

        assertThat(u.getSenhaHash()).isEqualTo("hash-novo");
        assertThat(u.isPrecisaTrocarSenha()).isFalse();
    }

    @Test
    void desativarEReativar() {
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        u.desativar();
        assertThat(u.isAtivo()).isFalse();
        u.reativar();
        assertThat(u.isAtivo()).isTrue();
    }

    @Test
    void desativarReativarETrocarSenhaRevogamOsTokens() {
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        int inicial = u.getVersaoToken();

        u.desativar();
        assertThat(u.getVersaoToken()).isEqualTo(inicial + 1);
        u.reativar();
        assertThat(u.getVersaoToken()).isEqualTo(inicial + 2);
        u.trocarSenha("hash-novo");
        assertThat(u.getVersaoToken()).isEqualTo(inicial + 3);
    }
}
