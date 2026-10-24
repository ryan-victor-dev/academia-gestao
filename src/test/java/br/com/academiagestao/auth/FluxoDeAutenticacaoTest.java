package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo como um cliente de verdade faria: faz login, pega o token
 * devolvido e usa nas chamadas seguintes. Nada é simulado além do HTTP.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FluxoDeAutenticacaoTest {

    private static final String SENHA_ADMIN = "senha-do-admin-123";
    private static final String SENHA_RECEPCAO = "senha-da-recepcao-123";

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void cadastrar() {
        limpeza.apagarTudo();
        repository.save(Usuario.daEquipe("Admin", "admin@academia.com",
                passwordEncoder.encode(SENHA_ADMIN), Perfil.ADMIN));
        repository.save(Usuario.daEquipe("Recepção", "recepcao@academia.com",
                passwordEncoder.encode(SENHA_RECEPCAO), Perfil.RECEPCAO));
    }

    private ResultActions login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "senha": "%s"}
                        """.formatted(email, senha)));
    }

    private String tokenDe(String email, String senha) throws Exception {
        String corpo = login(email, senha)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.token");
    }

    /** Com o e-mail desligado (o padrão, ADR 0038), ninguém precisa confirmar o e-mail. */
    @Test
    void emailDesligadoNaoExigeConfirmacao() throws Exception {
        login("recepcao@academia.com", SENHA_RECEPCAO).andExpect(jsonPath("$.precisaConfirmarEmail").value(false));
        String token = tokenDe("recepcao@academia.com", SENHA_RECEPCAO);

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.precisaConfirmarEmail").value(false));
        mvc.perform(get("/api/planos").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/email/codigo").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void loginOkEOTokenDaAcessoAoMe() throws Exception {
        String token = tokenDe("admin@academia.com", SENHA_ADMIN);

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@academia.com"))
                .andExpect(jsonPath("$.perfil").value("ADMIN"));
    }

    @Test
    void loginOkDeAdminDaAcessoARotaDeAdmin() throws Exception {
        String token = tokenDe("admin@academia.com", SENHA_ADMIN);

        mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void senhaErradaDevolve401SemToken() throws Exception {
        login("admin@academia.com", "senha-errada-123")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Credenciais inválidas"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void semTokenDevolve401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Não autenticado"));
    }

    @Test
    void tokenInvalidoDevolve401() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer token-que-nao-existe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Não autenticado"));
    }

    @Test
    void tokenAdulteradoParaVirarAdminDevolve401() throws Exception {
        // Ataque clássico: pegar um token legítimo de RECEPCAO e trocar o perfil no payload.
        // O payload é só Base64, qualquer um edita; o que impede é a assinatura não bater mais.
        String[] partes = tokenDe("recepcao@academia.com", SENHA_RECEPCAO).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8);
        String payloadAdulterado = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.replace("\"RECEPCAO\"", "\"ADMIN\"").getBytes(StandardCharsets.UTF_8));
        String adulterado = partes[0] + "." + payloadAdulterado + "." + partes[2];

        mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + adulterado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void perfilSemPermissaoDevolve403() throws Exception {
        String token = tokenDe("recepcao@academia.com", SENHA_RECEPCAO);

        mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Acesso negado"));
    }
}
