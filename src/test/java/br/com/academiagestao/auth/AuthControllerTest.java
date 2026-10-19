package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

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
        repository.save(Usuario.daEquipe("Ana", "ana@academia.com",
                passwordEncoder.encode("senha-da-ana-123"), Perfil.RECEPCAO));
        Usuario inativo = Usuario.daEquipe("Bia", "bia@academia.com",
                passwordEncoder.encode("senha-da-bia-123"), Perfil.INSTRUTOR);
        inativo.desativar();
        repository.save(inativo);
    }

    private static String corpo(String email, String senha) {
        return """
                {"email": "%s", "senha": "%s"}
                """.formatted(email, senha);
    }

    @Test
    void loginCorretoDevolveToken() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("ana@academia.com", "senha-da-ana-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andExpect(jsonPath("$.expiraEm").isString());
    }

    @Test
    void senhaErradaEUsuarioInativoTemExatamenteAMesmaResposta() throws Exception {
        String senhaErrada = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("ana@academia.com", "senha-errada-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        String inativo = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("bia@academia.com", "senha-da-bia-123")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String inexistente = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("ninguem@academia.com", "qualquer-senha")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(senhaErrada).isEqualTo(inativo).isEqualTo(inexistente);
    }

    @Test
    void corpoInvalidoDevolve400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("nao-e-email", "")))
                .andExpect(status().isBadRequest());
    }
}
