package br.com.academiagestao.auth;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Revisão de 2026-10-17 (M1): um token fazia mais de mil requisições por segundo sem nenhum
 * 429. Aqui com 5 por minuto, para caber num teste.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "academia.requisicoes.por-minuto=5")
@AutoConfigureMockMvc
class LimiteDeRequisicoesNaApiTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private String email;

    @AfterEach
    void limpar() {
        if (email != null) {
            jdbc.update("DELETE FROM tentativas_login WHERE chave = ?", email);
            jdbc.update("DELETE FROM usuarios WHERE email = ?", email);
        }
    }

    private static RequestPostProcessor admin(String id) {
        return jwt().jwt(token -> token.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void passouDoLimiteRecebe429ComRetryAfterEOutroUsuarioContinua() throws Exception {
        String ana = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/planos").with(admin(ana))).andExpect(status().isOk());
        }

        mvc.perform(get("/api/planos").with(admin(ana)))
                .andExpect(status().isTooManyRequests())
                // 5 por minuto = uma ficha a cada 12 s.
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Muitas requisições"));
        // O limite é por usuário, e não da aplicação inteira.
        mvc.perform(get("/api/planos").with(admin(UUID.randomUUID().toString()))).andExpect(status().isOk());
    }

    @Test
    void valeParaQualquerRotaAutenticadaMesmoAsQueDao403() throws Exception {
        String bia = UUID.randomUUID().toString();
        RequestPostProcessor aluno = jwt().jwt(token -> token.subject(bia))
                .authorities(new SimpleGrantedAuthority("ROLE_ALUNO"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/usuarios").with(aluno)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/usuarios").with(aluno)).andExpect(status().isTooManyRequests());
    }

    @Test
    void semTokenNaoEntraNaConta() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/planos")).andExpect(status().isUnauthorized());
        }
    }

    /** Com token de verdade: o filtro precisa vir depois da validação do token, que diz quem é. */
    @Test
    void comTokenDeVerdade() throws Exception {
        email = "limite-" + UUID.randomUUID() + "@teste.local";
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Limite', ?, ?, 'RECEPCAO')",
                UUID.randomUUID(), email, passwordEncoder.encode(SENHA));
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + JsonPath.read(corpo, "$.token");

        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isTooManyRequests());
    }
}
