package br.com.academiagestao.auth;

import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Token e limite de login seguem o relógio único da aplicação (ADR 0023), e não o do sistema. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RelogioDaAutenticacaoTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    JwtProperties jwt;

    @Autowired
    LimitadorDeTentativas limitador;

    private final String email = "relogio-" + UUID.randomUUID() + "@teste.local";

    @AfterEach
    void limpar() {
        relogio.restaurar();
        jdbc.update("DELETE FROM tentativas_login WHERE chave = ?", email);
        // A do IP, gravada com o relógio adiantado, contaria na janela dos outros testes.
        jdbc.update("DELETE FROM tentativas_login WHERE momento > now() + interval '1 minute'");
        jdbc.update("DELETE FROM usuarios WHERE email = ?", email);
    }

    private String entrar() throws Exception {
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Relógio', ?, ?, 'RECEPCAO')",
                UUID.randomUUID(), email, passwordEncoder.encode(SENHA));
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void tokenNasceComOAgoraDoRelogio() throws Exception {
        relogio.parar();
        relogio.adiantar(Duration.ofDays(3));

        String corpo = entrar();

        assertThat(Instant.parse(JsonPath.read(corpo, "$.expiraEm")))
                .isEqualTo(relogio.instant().plus(jwt.expiracao()));
    }

    @Test
    void tokenVencePeloRelogio() throws Exception {
        String token = "Bearer " + JsonPath.read(entrar(), "$.token");
        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk());

        // Além da tolerância de 60 s do Spring.
        relogio.adiantar(jwt.expiracao().plusMinutes(2));

        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isUnauthorized());
    }

    @Test
    void limpezaDasTentativasUsaORelogio() {
        jdbc.update("INSERT INTO tentativas_login (tipo, chave, ip, momento) VALUES ('CONTA', ?, '203.0.113.7', ?)",
                email, Timestamp.from(Instant.now()));

        limitador.limparAntigos();
        assertThat(tentativas()).isEqualTo(1);

        relogio.adiantar(Duration.ofDays(2));
        limitador.limparAntigos();
        assertThat(tentativas()).isZero();
    }

    private Integer tentativas() {
        return jdbc.queryForObject("SELECT count(*) FROM tentativas_login WHERE chave = ?", Integer.class, email);
    }
}
