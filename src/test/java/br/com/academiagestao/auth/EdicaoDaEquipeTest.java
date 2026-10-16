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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O ADMIN corrige o nome e muda o perfil de quem é da equipe. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EdicaoDaEquipeTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private Usuario admin;
    private Usuario rita;
    private String tokenDoAdmin;

    @BeforeEach
    void cadastrar() throws Exception {
        jdbc.update("DELETE FROM usuarios");
        admin = daEquipe("admin@academia.com", Perfil.ADMIN);
        rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        tokenDoAdmin = login("admin@academia.com");
    }

    private Usuario daEquipe(String email, Perfil perfil) {
        return repository.save(Usuario.daEquipe(email, email, passwordEncoder.encode(SENHA), perfil));
    }

    private String login(String email) throws Exception {
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.token");
    }

    private ResultActions editar(String token, UUID id, String nome, String perfil) throws Exception {
        String perfilJson = perfil == null ? "null" : "\"" + perfil + "\"";
        return mvc.perform(put("/api/usuarios/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"%s\", \"perfil\": %s}".formatted(nome, perfilJson)));
    }

    private ResultActions listarUsuarios(String token) throws Exception {
        return mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + token));
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void corrigirONomeNaoDerrubaASessao() throws Exception {
        String sessaoDaRita = login("rita@academia.com");

        editar(tokenDoAdmin, rita.getId(), "Rita de Cássia", "RECEPCAO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Rita de Cássia"))
                .andExpect(jsonPath("$.perfil").value("RECEPCAO"));

        me(sessaoDaRita).andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Rita de Cássia"));
    }

    @Test
    void promoverDerrubaASessaoAntigaEONovoLoginJaTemOPerfilNovo() throws Exception {
        String sessaoDaRita = login("rita@academia.com");
        listarUsuarios(sessaoDaRita).andExpect(status().isForbidden());

        editar(tokenDoAdmin, rita.getId(), "Rita", "ADMIN").andExpect(status().isOk());

        me(sessaoDaRita).andExpect(status().isUnauthorized());
        listarUsuarios(login("rita@academia.com")).andExpect(status().isOk());
    }

    @Test
    void adminRebaixadoPerdeOsPoderesNaHora() throws Exception {
        Usuario gerente = daEquipe("gerente@academia.com", Perfil.ADMIN);
        String sessaoDoGerente = login("gerente@academia.com");
        listarUsuarios(sessaoDoGerente).andExpect(status().isOk());

        editar(tokenDoAdmin, gerente.getId(), "Gerente", "INSTRUTOR").andExpect(status().isOk());

        // Sem a revogação, o token antigo ainda diria "perfil: ADMIN" por até 15 minutos.
        listarUsuarios(sessaoDoGerente).andExpect(status().isUnauthorized());
        listarUsuarios(login("gerente@academia.com")).andExpect(status().isForbidden());
    }

    @Test
    void ninguemMudaOProprioPerfilMasPodeCorrigirOProprioNome() throws Exception {
        editar(tokenDoAdmin, admin.getId(), "Admin", "RECEPCAO")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Você não pode mudar o seu próprio perfil"));

        editar(tokenDoAdmin, admin.getId(), "Dona da Academia", "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Dona da Academia"));
        listarUsuarios(tokenDoAdmin).andExpect(status().isOk());
    }

    @Test
    void perfilAlunoNaoEhDadoNemTiradoPorAqui() throws Exception {
        editar(tokenDoAdmin, rita.getId(), "Rita", "ALUNO").andExpect(status().isBadRequest());

        UUID alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Ana', '52998224725')", alunoId);
        Usuario loginDaAna = repository.save(Usuario.deAluno("Ana", "ana@email.com", "hash", alunoId));
        editar(tokenDoAdmin, loginDaAna.getId(), "Ana", "RECEPCAO").andExpect(status().isBadRequest());
        jdbc.update("DELETE FROM usuarios WHERE aluno_id = ?", alunoId);
        jdbc.update("DELETE FROM alunos WHERE id = ?", alunoId);
    }

    @Test
    void dadosInvalidosDevolvem400() throws Exception {
        editar(tokenDoAdmin, rita.getId(), "", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.length()").value(2));
    }

    @Test
    void usuarioInexistenteDevolve404() throws Exception {
        editar(tokenDoAdmin, UUID.randomUUID(), "X", "RECEPCAO").andExpect(status().isNotFound());
    }

    @Test
    void recepcaoNaoEditaNinguem() throws Exception {
        editar(login("rita@academia.com"), rita.getId(), "Rita", "ADMIN").andExpect(status().isForbidden());
    }
}
