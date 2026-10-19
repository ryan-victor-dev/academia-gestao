package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Força bruta no login: limite por IP, por conta e IP, e por conta no total, com 429,
 * Retry-After e registro no log.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "academia.login.tentativas-por-ip=5",
        "academia.login.falhas-por-conta-e-ip=3",
        "academia.login.falhas-por-conta-no-total=6"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LimiteDeLoginTest {

    private static final String SENHA = "senha-da-ana-123";

    @Autowired
    LimpezaDoBanco limpeza;

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
        jdbc.update("DELETE FROM tentativas_login"); // contadores zerados a cada teste
        limpeza.apagarTudo();
        repository.save(Usuario.daEquipe("Ana", "ana@academia.com", passwordEncoder.encode(SENHA), Perfil.RECEPCAO));
    }

    private ResultActions login(String ip, String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(requisicao -> { requisicao.setRemoteAddr(ip); return requisicao; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, senha)));
    }

    @Test
    void falhasDeUmIpBloqueiamEsseIpAteComASenhaCertaMasNaoODono() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("10.0.0.1", "ana@academia.com", "chute-" + i + "-xxxxxx").andExpect(status().isUnauthorized());
        }

        // Se a senha certa passasse durante o bloqueio, o atacante saberia que acertou.
        login("10.0.0.1", "ana@academia.com", SENHA)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("\\d+")))
                .andExpect(jsonPath("$.title").value("Muitas tentativas"));
        // Quem sabe só o e-mail não tranca o dono para fora (diagnóstico de 2026-10-18).
        login("10.0.0.2", "ana@academia.com", SENHA).andExpect(status().isOk());
    }

    @Test
    void falhasEspalhadasPorMuitosIpsBloqueiamAContaInteiraNoTeto() throws Exception {
        for (int i = 0; i < 6; i++) {
            login("10.0.5." + i, "ana@academia.com", "chute-" + i + "-xxxxxx").andExpect(status().isUnauthorized());
        }

        // Nenhum IP chegou a 3 falhas, mas a soma chegou ao teto: nem a senha certa, de um IP novo.
        login("10.0.5.99", "ana@academia.com", SENHA).andExpect(status().isTooManyRequests());
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
            login("10.0.1.1", "ninguem@academia.com", "chute-xxxxxxx").andExpect(status().isUnauthorized());
        }
        // Mesma resposta de uma conta real: o bloqueio não revela quem está cadastrado.
        login("10.0.1.1", "ninguem@academia.com", "chute-xxxxxxx").andExpect(status().isTooManyRequests());
    }

    @Test
    void loginCertoZeraAsFalhasDoProprioIp() throws Exception {
        login("10.0.2.1", "ana@academia.com", "chute-1-xxxxxx").andExpect(status().isUnauthorized());
        login("10.0.2.1", "ana@academia.com", "chute-2-xxxxxx").andExpect(status().isUnauthorized());
        login("10.0.2.1", "ana@academia.com", SENHA).andExpect(status().isOk());

        // Sem zerar, seriam 3 falhas deste IP (o limite) e a senha certa daria 429.
        login("10.0.2.1", "ana@academia.com", "chute-3-xxxxxx").andExpect(status().isUnauthorized());
        login("10.0.2.1", "ana@academia.com", SENHA).andExpect(status().isOk());
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
