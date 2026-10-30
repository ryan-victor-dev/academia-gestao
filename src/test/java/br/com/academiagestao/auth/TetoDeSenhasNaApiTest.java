package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Enxurrada de logins (ADR 0046, item 3): acima do teto de senhas ao mesmo tempo, o login recebe
 * 503 com Retry-After, e o resto do sistema segue respondendo. Teto 1 e sem espera, para o teste
 * ocupar a única vaga.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"academia.senhas.teto=1", "academia.senhas.espera=0ms"})
@AutoConfigureMockMvc
class TetoDeSenhasNaApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder senhas;

    @Autowired
    JdbcTemplate jdbc;

    private final String email = "teto-" + UUID.randomUUID() + "@teste.local";

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM tentativas_login WHERE chave = ?", email);
        jdbc.update("DELETE FROM acessos WHERE conta = ?", email);
    }

    /** Uma senha que segura a vaga do BCrypt até o teste soltar: o BCrypt lê a senha já com a vaga. */
    private static final class SenhaQueSegura implements CharSequence {
        final CountDownLatch lida = new CountDownLatch(1);
        final CountDownLatch soltar = new CountDownLatch(1);

        @Override
        public String toString() {
            lida.countDown();
            try {
                soltar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "qualquer";
        }

        @Override
        public int length() {
            return toString().length();
        }

        @Override
        public char charAt(int i) {
            return toString().charAt(i);
        }

        @Override
        public CharSequence subSequence(int de, int ate) {
            return toString().subSequence(de, ate);
        }
    }

    private ResultActions login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"senha\": \"senha-errada-123\"}"));
    }

    @Test
    void acimaDoTetoOLoginRecebe503EORestoContinua() throws Exception {
        String hash = senhas.encode("outra");
        SenhaQueSegura segura = new SenhaQueSegura();
        CompletableFuture<Boolean> ocupada = CompletableFuture.supplyAsync(() -> senhas.matches(segura, hash));
        try {
            assertThat(segura.lida.await(5, TimeUnit.SECONDS)).isTrue();

            login().andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.title").value("Sistema ocupado"))
                    .andExpect(jsonPath("$.detail").value("Muitos acessos ao mesmo tempo. Tente de novo em alguns segundos."));
            // O que não confere senha não espera pela vaga.
            mvc.perform(get("/api/auth/opcoes")).andExpect(status().isOk());
        } finally {
            segura.soltar.countDown();
        }
        assertThat(ocupada.get(5, TimeUnit.SECONDS)).isFalse();

        // Com a vaga livre, o login volta a conferir a senha (e recusa a errada).
        login().andExpect(status().isUnauthorized());
    }
}
