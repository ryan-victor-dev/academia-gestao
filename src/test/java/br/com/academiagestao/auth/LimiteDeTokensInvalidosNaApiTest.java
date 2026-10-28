package br.com.academiagestao.auth;

import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Diagnóstico de 27/10, B7 (decisão do dono): um IP mandava 1.480 tokens inválidos por segundo, todos
 * 401, sem limite. Aqui com 3 por minuto, para caber num teste. Cada teste usa um IP próprio.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "academia.tokens-invalidos.por-minuto=3")
@AutoConfigureMockMvc
class LimiteDeTokensInvalidosNaApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    RelogioDeTeste relogio;

    @AfterEach
    void restaurar() {
        relogio.restaurar();
    }

    private static String ip() {
        return "10.88." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256);
    }

    private ResultActions comToken(String ip, String token) throws Exception {
        return mvc.perform(get("/api/planos").header("Authorization", "Bearer " + token).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }));
    }

    private ResultActions semToken(String ip) throws Exception {
        return mvc.perform(get("/api/auth/opcoes").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }));
    }

    @Test
    void ipComTokensInvalidosDemaisRecebe429() throws Exception {
        String ip = ip();
        for (int i = 0; i < 3; i++) {
            comToken(ip, "lixo-" + i).andExpect(status().isUnauthorized());
        }

        comToken(ip, "lixo-4").andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        // O 429 não gasta ficha: quem insiste não se prende por mais tempo.
        comToken(ip, "lixo-5").andExpect(status().isTooManyRequests());
    }

    @Test
    void outroIpESemTokenNaoSaoAfetados() throws Exception {
        String ip = ip();
        for (int i = 0; i < 3; i++) {
            comToken(ip, "lixo-" + i).andExpect(status().isUnauthorized());
        }
        comToken(ip, "lixo").andExpect(status().isTooManyRequests());

        comToken(ip(), "lixo").andExpect(status().isUnauthorized());
        semToken(ip).andExpect(status().isOk());
    }

    /** Só gasta quem mandou um token: o 401 de quem não mandou nenhum (a tela antes do login) não conta. */
    @Test
    void semTokenNaoGasta() throws Exception {
        String ip = ip();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/planos").with(r -> {
                r.setRemoteAddr(ip);
                return r;
            })).andExpect(status().isUnauthorized());
        }

        comToken(ip, "lixo").andExpect(status().isUnauthorized());
    }

    @Test
    void asFichasVoltamComOTempo() throws Exception {
        String ip = ip();
        Instant agora = Instant.now();
        relogio.fixar(agora);
        for (int i = 0; i < 3; i++) {
            comToken(ip, "lixo-" + i).andExpect(status().isUnauthorized());
        }
        comToken(ip, "lixo").andExpect(status().isTooManyRequests());

        relogio.fixar(agora.plus(Duration.ofSeconds(21)));
        comToken(ip, "lixo").andExpect(status().isUnauthorized());
        comToken(ip, "lixo").andExpect(status().isTooManyRequests());
    }
}
