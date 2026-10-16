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

    @Test
    void redefinirComSenhaTemporariaExigeTrocaERevogaTokens() {
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        int versao = u.getVersaoToken();

        u.redefinirComSenhaTemporaria("hash-temporario");

        assertThat(u.getSenhaHash()).isEqualTo("hash-temporario");
        assertThat(u.isPrecisaTrocarSenha()).isTrue();
        assertThat(u.getVersaoToken()).isGreaterThan(versao);
    }

    @Test
    void mudarPerfilRevogaTokensSoQuandoMudaDeVerdade() {
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        int versao = u.getVersaoToken();

        u.mudarPerfil(Perfil.RECEPCAO);
        assertThat(u.getVersaoToken()).isEqualTo(versao);

        u.mudarPerfil(Perfil.ADMIN);
        assertThat(u.getPerfil()).isEqualTo(Perfil.ADMIN);
        assertThat(u.getVersaoToken()).isEqualTo(versao + 1);
    }

    @Test
    void perfilAlunoNaoEntraNemSaiPorMudarPerfil() {
        Usuario equipe = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        Usuario aluno = Usuario.deAluno("Bia", "bia@x.com", HASH, UUID.randomUUID());

        assertThatThrownBy(() -> equipe.mudarPerfil(Perfil.ALUNO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aluno.mudarPerfil(Perfil.ADMIN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renomearExigeNomeENaoRevogaTokens() {
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        int versao = u.getVersaoToken();

        u.renomear("  Ana Maria ");

        assertThat(u.getNome()).isEqualTo("Ana Maria");
        assertThat(u.getVersaoToken()).isEqualTo(versao);
        assertThatThrownBy(() -> u.renomear(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
