package br.com.academiagestao.auth;

import br.com.academiagestao.auth.CodigosDeEmail.Finalidade;
import br.com.academiagestao.compartilhado.CadastroParaPrimeiroAcesso;
import br.com.academiagestao.compartilhado.CadastroParaPrimeiroAcesso.AlunoDoCadastro;
import br.com.academiagestao.compartilhado.Correio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O pedido de código ("esqueci a senha" e primeiro acesso) gasta um BCrypt em todo caminho: quem
 * não existe, quem existe e recebe o código (o BCrypt fica no {@code CodigosDeEmail.emitir}), e
 * quem existe mas já pediu demais. Sem isso, o tempo de resposta contaria quem tem conta (M1 do
 * diagnóstico de 30/10: ~12 ms contra ~62 ms).
 */
class TempoDoPedidoDeCodigoTest {

    private static final String EMAIL = "ana@academia.com";
    private static final String CPF = "52998224725";
    private static final String IP = "203.0.113.20";

    private final UsuarioRepository repository = mock(UsuarioRepository.class);
    private final CodigosDeEmail codigos = mock(CodigosDeEmail.class);
    private final Correio correio = mock(Correio.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final CadastroParaPrimeiroAcesso cadastro = mock(CadastroParaPrimeiroAcesso.class);

    private final RecuperacaoDeSenha recuperacao = new RecuperacaoDeSenha(repository, codigos, correio,
            passwordEncoder, mock(LimitadorDeTentativas.class), mock(TrilhaDeUsuarios.class),
            mock(PlatformTransactionManager.class), Runnable::run);
    private final PrimeiroAcesso primeiroAcesso = new PrimeiroAcesso(cadastro, repository, codigos, correio,
            passwordEncoder, mock(LimitadorDeTentativas.class), mock(TrilhaDeUsuarios.class), Clock.systemUTC(),
            mock(PlatformTransactionManager.class), Runnable::run);

    @BeforeEach
    void ligarOEmail() {
        when(correio.ligado()).thenReturn(true);
    }

    private void contaQuePodeRecuperar() {
        Usuario usuario = Usuario.daEquipe("Ana", EMAIL, "hash", Perfil.RECEPCAO);
        usuario.confirmarEmail(EMAIL, Instant.now());
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario));
    }

    private void alunoSemLogin() {
        when(cadastro.ativoCom(CPF, EMAIL)).thenReturn(Optional.of(new AlunoDoCadastro(UUID.randomUUID(), "Ana", EMAIL)));
    }

    @Test
    void recuperacaoSemContaGastaUmBCrypt() {
        recuperacao.pedir(EMAIL, IP);

        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    void recuperacaoComContaGastaOBCryptDoCodigo() {
        contaQuePodeRecuperar();
        when(codigos.emitir(any(), eq(Finalidade.RECUPERACAO), eq(EMAIL))).thenReturn("123456");

        recuperacao.pedir(EMAIL, IP);

        verify(codigos).emitir(any(), eq(Finalidade.RECUPERACAO), eq(EMAIL));
        verify(passwordEncoder, times(0)).encode(anyString());
    }

    @Test
    void recuperacaoComMuitosCodigosGastaUmBCryptComoQuemNaoTemConta() {
        contaQuePodeRecuperar();
        when(codigos.emitir(any(), eq(Finalidade.RECUPERACAO), eq(EMAIL)))
                .thenThrow(new MuitosCodigosException(Duration.ofSeconds(30)));

        recuperacao.pedir(EMAIL, IP);

        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    void primeiroAcessoSemAlunoGastaUmBCrypt() {
        primeiroAcesso.pedir(CPF, EMAIL, IP);

        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    void primeiroAcessoComAlunoGastaOBCryptDoCodigo() {
        alunoSemLogin();
        when(codigos.emitir(any(), eq(Finalidade.PRIMEIRO_ACESSO), eq(EMAIL))).thenReturn("123456");

        primeiroAcesso.pedir(CPF, EMAIL, IP);

        verify(codigos).emitir(any(), eq(Finalidade.PRIMEIRO_ACESSO), eq(EMAIL));
        verify(passwordEncoder, times(0)).encode(anyString());
    }

    @Test
    void primeiroAcessoComMuitosCodigosGastaUmBCryptComoQuemNaoEAluno() {
        alunoSemLogin();
        when(codigos.emitir(any(), eq(Finalidade.PRIMEIRO_ACESSO), eq(EMAIL)))
                .thenThrow(new MuitosCodigosException(Duration.ofSeconds(30)));

        primeiroAcesso.pedir(CPF, EMAIL, IP);

        verify(passwordEncoder, times(1)).encode(anyString());
    }
}
