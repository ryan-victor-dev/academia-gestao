package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
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

    /** Aluno inativo (ADR 0029): o login fica, só de leitura; cada mudança derruba os tokens. */
    @Test
    void loginDeAlunoFicaSoDeLeituraEVoltaAoNormal() {
        Usuario u = Usuario.deAluno("Ana", "ana@x.com", HASH, java.util.UUID.randomUUID());
        int inicial = u.getVersaoToken();
        assertThat(u.isSomenteLeitura()).isFalse();

        u.restringirALeitura();
        assertThat(u.isSomenteLeitura()).isTrue();
        assertThat(u.isAtivo()).isTrue();
        assertThat(u.getVersaoToken()).isEqualTo(inicial + 1);

        u.liberarDaLeitura();
        assertThat(u.isSomenteLeitura()).isFalse();
        assertThat(u.getVersaoToken()).isEqualTo(inicial + 2);
    }

    @Test
    void soLoginDeAlunoFicaSoDeLeitura() {
        Usuario u = Usuario.daEquipe("Rita", "rita@x.com", HASH, Perfil.RECEPCAO);

        assertThatThrownBy(u::restringirALeitura).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(u::liberarDaLeitura).isInstanceOf(IllegalStateException.class);
        assertThat(u.isSomenteLeitura()).isFalse();
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

    @Test
    void recusaDadosObrigatoriosVaziosOuInvalidos() {
        UUID aluno = UUID.randomUUID();
        for (String nome : new String[]{null, "  "}) {
            assertThatThrownBy(() -> Usuario.daEquipe(nome, "a@x.com", HASH, Perfil.RECEPCAO))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nome");
            assertThatThrownBy(() -> Usuario.deAluno(nome, "a@x.com", HASH, aluno))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nome");
        }
        for (String email : new String[]{null, "sem-arroba.com"}) {
            assertThatThrownBy(() -> Usuario.daEquipe("Ana", email, HASH, Perfil.RECEPCAO))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("e-mail");
            assertThatThrownBy(() -> Usuario.deAluno("Ana", email, HASH, aluno))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("e-mail");
        }
        for (String hash : new String[]{null, " "}) {
            assertThatThrownBy(() -> Usuario.daEquipe("Ana", "a@x.com", hash, Perfil.RECEPCAO))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("senhaHash");
            assertThatThrownBy(() -> Usuario.deAluno("Ana", "a@x.com", hash, aluno))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("senhaHash");
        }
    }

    @Test
    void renomearETrocarSenhaRecusamValorNulo() {
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        int versao = u.getVersaoToken();

        assertThatThrownBy(() -> u.renomear(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> u.trocarSenha(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> u.trocarSenha("  ")).isInstanceOf(IllegalArgumentException.class);
        // Nada mudou: nem a senha, nem os tokens.
        assertThat(u.getSenhaHash()).isEqualTo(HASH);
        assertThat(u.getVersaoToken()).isEqualTo(versao);
    }

    /** O código confirma só o e-mail para onde foi (ADR 0038). */
    @Test
    void confirmaOEmailSoComOCodigoQueFoiParaEle() {
        Usuario u = Usuario.daEquipe("Ana", "ana@academia.com", HASH, Perfil.RECEPCAO);
        Instant agora = Instant.parse("2026-10-27T12:00:00.123456789Z");

        assertThatThrownBy(() -> u.confirmarEmail("outro@academia.com", agora)).isInstanceOf(IllegalStateException.class);
        assertThat(u.getEmailConfirmadoEm()).isNull();

        u.confirmarEmail("ana@academia.com", agora);
        assertThat(u.getEmailConfirmadoEm()).isEqualTo(Instant.parse("2026-10-27T12:00:00.123456Z"));
        assertThat(u.getCriadoEm()).isNotNull();
    }

    @Test
    void corrigeOEmailSoAntesDeConfirmar() {
        Usuario u = Usuario.daEquipe("Ana", "ana@academia.com", HASH, Perfil.RECEPCAO);

        u.corrigirEmail("  Ana.Souza@Academia.COM ");
        assertThat(u.getEmail()).isEqualTo("ana.souza@academia.com");

        u.confirmarEmail("ana.souza@academia.com", Instant.parse("2026-10-27T12:00:00Z"));
        assertThatThrownBy(() -> u.corrigirEmail("ana@academia.com")).isInstanceOf(IllegalStateException.class);
        assertThat(u.getEmail()).isEqualTo("ana.souza@academia.com");
    }

    @Test
    void emailDoCadastroSoTiraAConfirmacaoQuandoMudaDeVerdade() {
        Usuario u = Usuario.deAluno("Ana", "ana@academia.com", HASH, UUID.randomUUID());
        Instant confirmado = Instant.parse("2026-10-27T12:00:00Z");
        u.confirmarEmail("ana@academia.com", confirmado);

        u.acompanharEmailDoCadastro(" ANA@academia.com ");
        assertThat(u.getEmailConfirmadoEm()).isEqualTo(confirmado);

        u.acompanharEmailDoCadastro("  Ana.Souza@Academia.COM ");
        assertThat(u.getEmail()).isEqualTo("ana.souza@academia.com");
        assertThat(u.getEmailConfirmadoEm()).isNull();

        assertThatThrownBy(() -> u.acompanharEmailDoCadastro(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> u.acompanharEmailDoCadastro("sem-arroba")).isInstanceOf(IllegalArgumentException.class);
        assertThat(u.getEmail()).isEqualTo("ana.souza@academia.com");
    }

    @Test
    void cpfDaEquipeGuardadoSoComOsDigitos() {
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.RECEPCAO);
        assertThat(u.getCpf()).isNull();

        u.informarCpf("529.982.247-25");

        assertThat(u.getCpf()).isEqualTo("52998224725");
        assertThatThrownBy(() -> u.informarCpf("529.982.247-24")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> u.informarCpf(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(u.getCpf()).isEqualTo("52998224725");
        assertThatThrownBy(() -> Usuario.deAluno("Caio", "caio@x.com", HASH, UUID.randomUUID()).informarCpf("52998224725"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nascimentoDaEquipeNemNoFuturoNemMaisDe120Anos() {
        // 30/10/2026 ao meio-dia em Brasília.
        Instant agora = Instant.parse("2026-10-30T15:00:00Z");
        Usuario u = Usuario.daEquipe("Ana", "ana@x.com", HASH, Perfil.INSTRUTOR);
        assertThat(u.getDataNascimento()).isNull();

        u.informarNascimento(LocalDate.of(1990, 5, 17), agora);

        assertThat(u.getDataNascimento()).isEqualTo(LocalDate.of(1990, 5, 17));
        assertThatThrownBy(() -> u.informarNascimento(LocalDate.of(2026, 10, 31), agora))
                .isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> u.informarNascimento(null, agora)).isInstanceOf(NullPointerException.class);
        assertThat(u.getDataNascimento()).isEqualTo(LocalDate.of(1990, 5, 17));
        assertThatThrownBy(() -> Usuario.deAluno("Caio", "caio@x.com", HASH, UUID.randomUUID())
                .informarNascimento(LocalDate.of(1990, 5, 17), agora)).isInstanceOf(IllegalStateException.class);
    }
}
