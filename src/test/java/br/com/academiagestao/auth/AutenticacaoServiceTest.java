package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.RegistroDeAcessos;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutenticacaoServiceTest {

    private static final String SENHA = "senha-correta-123";
    private static final String IP = "203.0.113.10";

    private final SecretKey chave = new SecretKeySpec(new byte[32], "HmacSHA256");
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final UsuarioRepository repository = mock(UsuarioRepository.class);
    private final AutenticacaoService service = new AutenticacaoService(repository, passwordEncoder,
            new GeradorDeToken(new NimbusJwtEncoder(new ImmutableSecret<>(chave)),
                    new JwtProperties("ignorado", Duration.ofMinutes(15), "academia-gestao"), Clock.systemUTC()),
            mock(LimitadorDeTentativas.class), mock(org.springframework.transaction.PlatformTransactionManager.class),
            mock(TrilhaDeUsuarios.class), mock(RegistroDeAcessos.class));

    private Usuario cadastrar(Perfil perfil) {
        Usuario usuario = Usuario.daEquipe("Ana", "ana@academia.com", passwordEncoder.encode(SENHA), perfil);
        when(repository.findByEmail("ana@academia.com")).thenReturn(Optional.of(usuario));
        return usuario;
    }

    @Test
    void loginCorretoDevolveTokenAssinadoComPerfilEExpiracaoCurta() {
        Usuario usuario = cadastrar(Perfil.RECEPCAO);

        // E-mail com maiúsculas e espaços também funciona: é normalizado antes da busca.
        GeradorDeToken.TokenEmitido emitido = service.autenticar(" Ana@Academia.com ", SENHA, IP);

        Jwt jwt = NimbusJwtDecoder.withSecretKey(chave).macAlgorithm(MacAlgorithm.HS256).build()
                .decode(emitido.token());
        assertThat(jwt.getSubject()).isEqualTo(usuario.getId().toString());
        assertThat(jwt.getClaimAsString("perfil")).isEqualTo("RECEPCAO");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("academia-gestao");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getExpiresAt()).isCloseTo(emitido.expiraEm(), within(Duration.ofSeconds(1)));
        assertThat(jwt.hasClaim("alunoId")).isFalse(); // só login de aluno leva alunoId
    }

    @Test
    void tokenDeAlunoLevaOIdDoCadastro() {
        UUID alunoId = UUID.randomUUID();
        Usuario aluno = Usuario.deAluno("Ana", "ana@email.com", passwordEncoder.encode(SENHA), alunoId);
        when(repository.findByEmail("ana@email.com")).thenReturn(Optional.of(aluno));

        Jwt jwt = NimbusJwtDecoder.withSecretKey(chave).macAlgorithm(MacAlgorithm.HS256).build()
                .decode(service.autenticar("ana@email.com", SENHA, IP).token());

        assertThat(jwt.getClaimAsString("perfil")).isEqualTo("ALUNO");
        assertThat(jwt.getClaimAsString("alunoId")).isEqualTo(alunoId.toString());
    }

    @Test
    void senhaErradaEhRecusada() {
        cadastrar(Perfil.ADMIN);

        assertThatThrownBy(() -> service.autenticar("ana@academia.com", "senha-errada", IP))
                .isInstanceOf(CredenciaisInvalidasException.class);
    }

    @Test
    void usuarioInativoEhRecusadoMesmoComSenhaCerta() {
        cadastrar(Perfil.ADMIN).desativar();

        assertThatThrownBy(() -> service.autenticar("ana@academia.com", SENHA, IP))
                .isInstanceOf(CredenciaisInvalidasException.class);
    }

    @Test
    void emailInexistenteEhRecusadoDoMesmoJeito() {
        when(repository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.autenticar("ninguem@academia.com", SENHA, IP))
                .isInstanceOf(CredenciaisInvalidasException.class);
    }

    @Test
    void senhaGiganteEhRecusadaSemEstourarErro() {
        cadastrar(Perfil.ADMIN);

        assertThatThrownBy(() -> service.autenticar("ana@academia.com", "a".repeat(200), IP))
                .isInstanceOf(CredenciaisInvalidasException.class);
    }

    // ADR 0020: a troca confere a senha fora de transação e grava numa transação curta. Se
    // a senha mudar no meio (outra troca, uma redefinição), a atual digitada já não vale.
    @Test
    void senhaTrocadaPorOutroNoMeioDaConferenciaEhRecusada() {
        Usuario ana = Usuario.daEquipe("Ana", "ana@academia.com", passwordEncoder.encode(SENHA), Perfil.RECEPCAO);
        Usuario anaComOutraSenha = Usuario.daEquipe("Ana", "ana@academia.com",
                passwordEncoder.encode("redefinida-no-meio-123"), Perfil.RECEPCAO);
        when(repository.findById(ana.getId())).thenReturn(Optional.of(ana), Optional.of(anaComOutraSenha));

        assertThatThrownBy(() -> service.trocarSenha(ana.getId(), SENHA, "uma-senha-nova-123", "10.0.0.1"))
                .isInstanceOf(br.com.academiagestao.compartilhado.DadosInvalidosException.class)
                .hasMessageContaining("senha atual incorreta");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }
}
