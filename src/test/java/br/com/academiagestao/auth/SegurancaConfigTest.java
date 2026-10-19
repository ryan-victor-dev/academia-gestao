package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SegurancaConfigTest {

    // Rota qualquer, sem controller: passou da segurança vira 404; barrado vira 401.
    private static final String ROTA_PROTEGIDA = "/api/rota-protegida";

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    JwtAuthenticationConverter converter;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    JdbcTemplate jdbc;

    private Usuario usuario;

    // Os tokens deste teste são de um usuário real: o validador confere no banco se ele
    // existe, está ativo e se a versão do token é a atual.
    @BeforeEach
    void cadastrarUsuario() {
        limpeza.apagarTudo();
        usuario = usuarioRepository.save(Usuario.daEquipe("Ana", "ana@academia.com", "hash", Perfil.ADMIN));
    }

    private String token(JwtEncoder encoder, UnaryOperator<JwtClaimsSet.Builder> ajuste) {
        Instant agora = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("academia-gestao")
                .subject(usuario.getId().toString())
                .issuedAt(agora)
                .expiresAt(agora.plus(Duration.ofMinutes(15)))
                .claim("perfil", "ADMIN")
                .claim("versaoToken", usuario.getVersaoToken());
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, ajuste.apply(claims).build())).getTokenValue();
    }

    @Test
    void respostasTrazemOsCabecalhosDeSeguranca() throws Exception {
        // Inclusive nos erros (401), que saem dos filtros de segurança.
        for (RequestBuilder requisicao : new RequestBuilder[]{
                get("/actuator/health"), get(ROTA_PROTEGIDA)}) {
            mvc.perform(requisicao)
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }

    @Test
    void healthEhPublico() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void semTokenDevolve401() throws Exception {
        mvc.perform(get(ROTA_PROTEGIDA))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
    }

    @Test
    void cabecalhoDo401NaoRevelaOMotivoInterno() throws Exception {
        JwtEncoder falsificador = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(new byte[32], "HmacSHA256")));
        Instant passado = Instant.now().minus(Duration.ofHours(1));

        // Assinatura errada, token expirado e lixo: todos com o mesmo cabeçalho, sem descrição.
        for (String token : new String[]{
                token(falsificador, c -> c),
                token(encoder, c -> c.issuedAt(passado.minusSeconds(900)).expiresAt(passado)),
                "isto.nao.eh-um-jwt"}) {
            mvc.perform(get(ROTA_PROTEGIDA).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""));
        }
        mvc.perform(get(ROTA_PROTEGIDA)).andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void tokenValidoPassaPelaSeguranca() throws Exception {
        mvc.perform(get(ROTA_PROTEGIDA).header("Authorization", "Bearer " + token(encoder, c -> c)))
                .andExpect(status().isNotFound());
    }

    @Test
    void tokenExpiradoDevolve401() throws Exception {
        Instant passado = Instant.now().minus(Duration.ofHours(1));
        String expirado = token(encoder, c -> c.issuedAt(passado.minusSeconds(900)).expiresAt(passado));

        mvc.perform(get(ROTA_PROTEGIDA).header("Authorization", "Bearer " + expirado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenAssinadoComOutraChaveDevolve401() throws Exception {
        JwtEncoder falsificador = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(new byte[32], "HmacSHA256")));

        mvc.perform(get(ROTA_PROTEGIDA).header("Authorization", "Bearer " + token(falsificador, c -> c)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeOutroEmissorDevolve401() throws Exception {
        mvc.perform(get(ROTA_PROTEGIDA)
                        .header("Authorization", "Bearer " + token(encoder, c -> c.issuer("outro-sistema"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessaoNaoEhCriada() throws Exception {
        mvc.perform(get(ROTA_PROTEGIDA).header("Authorization", "Bearer " + token(encoder, c -> c)))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void perfilDaClaimViraRole() {
        Jwt jwt = Jwt.withTokenValue("x")
                .header("alg", "HS256")
                .subject("123")
                .claim("perfil", "INSTRUTOR")
                .build();

        // convert() e getAuthority() são @Nullable no Spring 7; a checagem explícita evita o aviso.
        AbstractAuthenticationToken autenticacao = Objects.requireNonNull(converter.convert(jwt));

        // O Spring Security 7 também adiciona FACTOR_BEARER (o fator usado para autenticar).
        assertThat(autenticacao.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .filteredOn(autoridade -> autoridade != null && autoridade.startsWith("ROLE_"))
                .containsExactly("ROLE_INSTRUTOR");
    }
}
