package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Força bruta no login: limite por IP e por conta, com 429, Retry-After e registro no log. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "academia.login.tentativas-por-ip=5",
        "academia.login.falhas-por-conta=3"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD) // contadores zerados a cada teste
class LimiteDeLoginTest {

    private static final String SENHA = "senha-da-ana-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cadastrar() {
        jdbc.update("DELETE FROM usuarios");
        repository.save(Usuario.daEquipe("Ana", "ana@academia.com", passwordEncoder.encode(SENHA), Perfil.RECEPCAO));
    }

    private ResultActions login(String ip, String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(requisicao -> { requisicao.setRemoteAddr(ip); return requisicao; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, senha)));
    }

    @Test
    void contaBloqueiaDepoisDeFalhasDeIpsDiferentesAteComASenhaCerta() throws Exception {
        login("10.0.0.1", "ana@academia.com", "chute-1-xxxxxx").andExpect(status().isUnauthorized());
        login("10.0.0.2", "ana@academia.com", "chute-2-xxxxxx").andExpect(status().isUnauthorized());
        login("10.0.0.3", "ana@academia.com", "chute-3-xxxxxx").andExpect(status().isUnauthorized());

        // Se a senha certa passasse durante o bloqueio, o atacante saberia que acertou.
        login("10.0.0.4", "ana@academia.com", SENHA)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("\\d+")))
                .andExpect(jsonPath("$.title").value("Muitas tentativas"));
    }

    @Test
    void ipBloqueiaDepoisDeTentativasDemaisMesmoEmContasDiferentes() throws Exception {
        for (int i = 0; i < 5; i++) {
            login("10.0.0.9", "conta" + i + "@academia.com", "qualquer-123").andExpect(status().isUnauthorized());
        }

        login("10.0.0.9", "ana@academia.com", SENHA).andExpect(status().isTooManyRequests());
        // De outro IP, a Ana entra normalmente.
        login("10.0.0.10", "ana@academia.com", SENHA).andExpect(status().isOk());
    }

    @Test
    void emailQueNaoExisteBloqueiaIgualAoQueExiste() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("10.0.1." + i, "ninguem@academia.com", "chute-xxxxxxx").andExpect(status().isUnauthorized());
        }
        // Mesma resposta de uma conta real: o bloqueio não revela quem está cadastrado.
        login("10.0.1.9", "ninguem@academia.com", "chute-xxxxxxx").andExpect(status().isTooManyRequests());
    }

    @Test
    void loginCertoZeraAsFalhas() throws Exception {
        login("10.0.2.1", "ana@academia.com", "chute-1-xxxxxx");
        login("10.0.2.2", "ana@academia.com", "chute-2-xxxxxx");
        login("10.0.2.3", "ana@academia.com", SENHA).andExpect(status().isOk());

        login("10.0.2.4", "ana@academia.com", "chute-3-xxxxxx").andExpect(status().isUnauthorized());
        login("10.0.2.5", "ana@academia.com", "chute-4-xxxxxx").andExpect(status().isUnauthorized());
        login("10.0.2.6", "ana@academia.com", SENHA).andExpect(status().isOk());
    }

    @Test
    void falhasEBloqueiosVaoParaOLogSemASenha(CapturedOutput log) throws Exception {
        for (int i = 0; i < 4; i++) {
            login("10.0.3.1", "Ana@Academia.com", "senha-digitada-errada-" + i);
        }

        assertThat(log.getAll())
                .contains("Login recusado: conta ana@academia.com / IP 10.0.3.1")
                .contains("Login bloqueado por excesso de tentativas: conta ana@academia.com / IP 10.0.3.1")
                .doesNotContain("senha-digitada-errada");
    }
}
