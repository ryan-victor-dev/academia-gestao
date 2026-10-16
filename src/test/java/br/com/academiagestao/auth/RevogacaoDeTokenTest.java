package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Um token já emitido deixa de valer na hora em que o usuário é desativado ou troca a senha. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RevogacaoDeTokenTest {

    private static final String SENHA = "senha-da-ana-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    JdbcTemplate jdbc;

    private Usuario ana;

    @BeforeEach
    void cadastrar() {
        jdbc.update("DELETE FROM usuarios");
        ana = repository.save(Usuario.daEquipe("Ana", "ana@academia.com", passwordEncoder.encode(SENHA), Perfil.ADMIN));
    }

    private String login(String senha) throws Exception {
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"ana@academia.com\", \"senha\": \"%s\"}".formatted(senha)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.token");
    }

    private ResultActions usarToken(String token) throws Exception {
        return mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + token));
    }

    private void mudarNoBanco(Consumer<Usuario> mudanca) {
        Usuario atual = repository.findById(ana.getId()).orElseThrow();
        mudanca.accept(atual);
        repository.save(atual);
    }

    @Test
    void desativarDerrubaOTokenNaHora() throws Exception {
        String token = login(SENHA);
        usarToken(token).andExpect(status().isOk());

        mudarNoBanco(Usuario::desativar);

        usarToken(token).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    @Test
    void desativadoDiretoNoBancoSemMexerNaVersaoTambemPerdeOToken() throws Exception {
        // Ex.: um ADMIN do banco rodando um UPDATE à mão. A versão não muda, mas o
        // validador também confere se o usuário está ativo.
        String token = login(SENHA);

        jdbc.update("UPDATE usuarios SET ativo = false WHERE id = ?", ana.getId());

        usarToken(token).andExpect(status().isUnauthorized());
    }

    @Test
    void reativarNaoRessuscitaOTokenAntigo() throws Exception {
        String token = login(SENHA);
        mudarNoBanco(Usuario::desativar);
        mudarNoBanco(Usuario::reativar);

        usarToken(token).andExpect(status().isUnauthorized());
        usarToken(login(SENHA)).andExpect(status().isOk());
    }

    @Test
    void trocarASenhaDerrubaAsOutrasSessoes() throws Exception {
        String sessaoNoCelular = login(SENHA);
        String sessaoNoComputador = login(SENHA);

        String corpo = mvc.perform(post("/api/auth/trocar-senha")
                        .header("Authorization", "Bearer " + sessaoNoComputador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\": \"%s\", \"novaSenha\": \"senha-nova-da-ana-123\"}".formatted(SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tokenNovo = JsonPath.read(corpo, "$.token");

        usarToken(sessaoNoCelular).andExpect(status().isUnauthorized());
        usarToken(sessaoNoComputador).andExpect(status().isUnauthorized());
        usarToken(tokenNovo).andExpect(status().isOk());
    }

    @Test
    void outrosUsuariosNaoSaoAfetados() throws Exception {
        Usuario bia = repository.save(Usuario.daEquipe("Bia", "bia@academia.com",
                passwordEncoder.encode(SENHA), Perfil.ADMIN));
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"bia@academia.com\", \"senha\": \"%s\"}".formatted(SENHA)))
                .andReturn().getResponse().getContentAsString();
        String tokenDaBia = JsonPath.read(corpo, "$.token");

        mudarNoBanco(Usuario::desativar);

        usarToken(tokenDaBia).andExpect(status().isOk());
    }

    @Test
    void tokenDeUsuarioQueNaoExisteMaisEhRecusado() throws Exception {
        String token = login(SENHA);
        jdbc.update("DELETE FROM usuarios WHERE id = ?", ana.getId());

        usarToken(token).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSemVersaoEhRecusado() throws Exception {
        // Como os tokens emitidos antes desta mudança: assinados certo, mas sem a versão.
        Instant agora = Instant.now();
        String semVersao = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("academia-gestao").subject(ana.getId().toString())
                        .issuedAt(agora).expiresAt(agora.plus(Duration.ofMinutes(15)))
                        .claim("perfil", "ADMIN").build())).getTokenValue();

        usarToken(semVersao).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenComSubjectQueNaoEhUuidEhRecusadoSemErro500() throws Exception {
        Instant agora = Instant.now();
        String estranho = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("academia-gestao").subject("nao-sou-uuid")
                        .issuedAt(agora).expiresAt(agora.plus(Duration.ofMinutes(15)))
                        .claim("perfil", "ADMIN").claim("versaoToken", 0).build())).getTokenValue();

        usarToken(estranho).andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioDesconhecidoComIdValidoEhRecusado() throws Exception {
        Instant agora = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("academia-gestao").subject(UUID.randomUUID().toString())
                        .issuedAt(agora).expiresAt(agora.plus(Duration.ofMinutes(15)))
                        .claim("perfil", "ADMIN").claim("versaoToken", 0).build())).getTokenValue();

        usarToken(token).andExpect(status().isUnauthorized());
    }
}
