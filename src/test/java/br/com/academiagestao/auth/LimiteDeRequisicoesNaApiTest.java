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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
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

    @Autowired
    RelogioDeTeste relogio;

    private String email;

    @AfterEach
    void limpar() {
        relogio.restaurar();
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

    /** As fichas voltam pelo relógio único da aplicação (ADR 0023), e não pelo do sistema. */
    @Test
    void fichaVoltaComORelogio() throws Exception {
        relogio.parar();
        String caio = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/planos").with(admin(caio))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/planos").with(admin(caio))).andExpect(status().isTooManyRequests());

        relogio.adiantar(Duration.ofSeconds(12));

        mvc.perform(get("/api/planos").with(admin(caio))).andExpect(status().isOk());
        mvc.perform(get("/api/planos").with(admin(caio))).andExpect(status().isTooManyRequests());
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
        String token = loginDeVerdade();

        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isTooManyRequests());
    }

    /**
     * Diagnóstico de 18/10 (S4): sem ficha, o 429 sai antes da consulta ao banco. Para ver a ordem,
     * o token é revogado depois de gastar as fichas: se o banco fosse consultado antes, viria 401.
     */
    @Test
    void semFichaRecebe429AntesDeOTokenSerConferidoNoBanco() throws Exception {
        String token = loginDeVerdade();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk());
        }

        revogarOsTokens();

        mvc.perform(get("/api/me").header("Authorization", token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"));
    }

    /** Quem só tem um token revogado (um token roubado, depois da troca de senha) não esgota o dono. */
    @Test
    void tokenRevogadoNaoGastaAsFichasDoDonoDaConta() throws Exception {
        String revogado = loginDeVerdade();
        revogarOsTokens();
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/me").header("Authorization", revogado)).andExpect(status().isUnauthorized());
        }

        String novo = login();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/me").header("Authorization", novo)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/me").header("Authorization", novo)).andExpect(status().isTooManyRequests());
    }

    private String loginDeVerdade() throws Exception {
        email = "limite-" + UUID.randomUUID() + "@teste.local";
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Limite', ?, ?, 'RECEPCAO')",
                UUID.randomUUID(), email, passwordEncoder.encode(SENHA));
        return login();
    }

    private String login() throws Exception {
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(corpo, "$.token");
    }

    /** Como a troca de senha faz: os tokens de antes deixam de valer. */
    private void revogarOsTokens() {
        jdbc.update("UPDATE usuarios SET versao_token = versao_token + 1 WHERE email = ?", email);
    }
}
