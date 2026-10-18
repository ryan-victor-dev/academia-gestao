package br.com.academiagestao.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrimeiroAdminInicializadorValidacaoTest {

    private final UsuarioRepository repository = mock(UsuarioRepository.class);

    private PrimeiroAdminInicializador comSenha(String senha) {
        when(repository.existsByPerfil(Perfil.ADMIN)).thenReturn(false);
        return new PrimeiroAdminInicializador(repository, new BCryptPasswordEncoder(),
                new AdminInicialProperties("Admin", "admin@academia.com", senha), mock(TrilhaDeUsuarios.class));
    }

    @Test
    void recusaSenhaCurta() {
        assertThatThrownBy(() -> comSenha("curta").run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12");
        verify(repository, never()).save(any());
    }

    @Test
    void recusaSenhaAcimaDoLimiteDoBcrypt() {
        assertThatThrownBy(() -> comSenha("a".repeat(73)).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("72");
    }

    @Test
    void semVariaveisDeAmbienteNaoCriaNada() throws Exception {
        comSenha(null).run(new DefaultApplicationArguments());

        verify(repository, never()).save(any());
    }
}
