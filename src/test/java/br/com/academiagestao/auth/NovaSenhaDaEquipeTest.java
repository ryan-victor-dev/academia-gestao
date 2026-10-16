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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Esqueci minha senha" da equipe: só o ADMIN gera, e nunca para outro ADMIN. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NovaSenhaDaEquipeTest {

    private static final String SENHA = "senha-original-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private Usuario admin;
    private Usuario recepcao;
    private String tokenDoAdmin;

    @BeforeEach
    void cadastrar() throws Exception {
        jdbc.update("DELETE FROM usuarios");
        String hash = passwordEncoder.encode(SENHA);
        admin = repository.save(Usuario.daEquipe("Admin", "admin@academia.com", hash, Perfil.ADMIN));
        recepcao = repository.save(Usuario.daEquipe("Rita", "rita@academia.com", hash, Perfil.RECEPCAO));
        tokenDoAdmin = token(login("admin@academia.com", SENHA));
    }

    private ResultActions login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, senha)));
    }

    private static String token(ResultActions resposta) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions novaSenha(String token, UUID usuarioId) throws Exception {
        return mvc.perform(post("/api/usuarios/" + usuarioId + "/nova-senha").header("Authorization", "Bearer " + token));
    }

    @Test
    void adminGeraNovaSenhaParaARecepcao() throws Exception {
        String sessaoAntigaDaRita = token(login("rita@academia.com", SENHA));

        String corpo = novaSenha(tokenDoAdmin, recepcao.getId())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("rita@academia.com"))
                .andReturn().getResponse().getContentAsString();
        String temporaria = JsonPath.read(corpo, "$.senhaTemporaria");

        // A senha antiga não entra mais, e a sessão que estava aberta caiu.
        login("rita@academia.com", SENHA).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + sessaoAntigaDaRita))
                .andExpect(status().isUnauthorized());
        // A temporária entra, mas exige troca.
        login("rita@academia.com", temporaria)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaTrocarSenha").value(true));
    }

    @Test
    void ninguemRedefineASenhaDeUmAdminNemAPropria() throws Exception {
        Usuario outroAdmin = repository.save(Usuario.daEquipe("Bia", "bia@academia.com",
                passwordEncoder.encode(SENHA), Perfil.ADMIN));

        novaSenha(tokenDoAdmin, outroAdmin.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Redefinição não permitida"));
        novaSenha(tokenDoAdmin, admin.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Redefinição não permitida"));
        login("bia@academia.com", SENHA).andExpect(status().isOk());
    }

    @Test
    void recepcaoNaoRedefineSenhaDaEquipe() throws Exception {
        Usuario instrutor = repository.save(Usuario.daEquipe("Caio", "caio@academia.com",
                passwordEncoder.encode(SENHA), Perfil.INSTRUTOR));
        String tokenDaRecepcao = token(login("rita@academia.com", SENHA));

        novaSenha(tokenDaRecepcao, instrutor.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acesso negado"));
        login("caio@academia.com", SENHA).andExpect(status().isOk());
    }

    @Test
    void usuarioInexistenteDevolve404() throws Exception {
        novaSenha(tokenDoAdmin, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void usuarioInativoDevolve409() throws Exception {
        recepcao.desativar();
        repository.save(recepcao);

        novaSenha(tokenDoAdmin, recepcao.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Usuário inativo"));
    }
}
