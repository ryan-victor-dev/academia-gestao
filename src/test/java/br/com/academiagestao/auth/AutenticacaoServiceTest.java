package br.com.academiagestao.auth;

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
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutenticacaoServiceTest {

    private static final String SENHA = "senha-correta-123";

    private final SecretKey chave = new SecretKeySpec(new byte[32], "HmacSHA256");
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final UsuarioRepository repository = mock(UsuarioRepository.class);
    private final AutenticacaoService service = new AutenticacaoService(repository, passwordEncoder,
            new GeradorDeToken(new NimbusJwtEncoder(new ImmutableSecret<>(chave)),
                    new JwtProperties("ignorado", Duration.ofMinutes(15), "academia-gestao")));

    private Usuario cadastrar(Perfil perfil) {
        Usuario usuario = Usuario.daEquipe("Ana", "ana@academia.com", passwordEncoder.encode(SENHA), perfil);
        when(repository.findByEmail("ana@academia.com")).thenReturn(Optional.of(usuario));
        return usuario;
    }

    @Test
    void loginCorretoDevolveTokenAssinadoComPerfilEExpiracaoCurta() {
        Usuario usuario = cadastrar(Perfil.RECEPCAO);

        // E-mail com maiúsculas e espaços também funciona: é normalizado antes da busca.
        GeradorDeToken.TokenEmitido emitido = service.autenticar(" Ana@Academia.com ", SENHA);

        Jwt jwt = NimbusJwtDecoder.withSecretKey(chave).macAlgorithm(MacAlgorithm.HS256).build()
                .decode(emitido.token());
        assertThat(jwt.getSubject()).isEqualTo(usuario.getId().toString());
        assertThat(jwt.getClaimAsString("perfil")).isEqualTo("RECEPCAO");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("academia-gestao");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getExpiresAt()).isCloseTo(emitido.expiraEm(), within(Duration.ofSeconds(1)));
    }

    @Test
    void senhaErradaEhRecusada() {
        cadastrar(Perfil.ADMIN);

        assertThatThrownBy(() -> service.autenticar("ana@academia.com", "senha-errada"))
                .isInstanceOf(CredenciaisInvalidasException.class);
    }

    @Test
    void usuarioInativoEhRecusadoMesmoComSenhaCerta() {
        cadastrar(Perfil.ADMIN).desativar();

        assertThatThrownBy(() -> service.autenticar("ana@academia.com", SENHA))
                .isInstanceOf(CredenciaisInvalidasException.class);
    }

    @Test
    void emailInexistenteEhRecusadoDoMesmoJeito() {
        when(repository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.autenticar("ninguem@academia.com", SENHA))
                .isInstanceOf(CredenciaisInvalidasException.class);
    }

    @Test
    void senhaGiganteEhRecusadaSemEstourarErro() {
        cadastrar(Perfil.ADMIN);

        assertThatThrownBy(() -> service.autenticar("ana@academia.com", "a".repeat(200)))
                .isInstanceOf(CredenciaisInvalidasException.class);
    }
}
