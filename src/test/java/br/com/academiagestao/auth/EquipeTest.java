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

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O ADMIN cadastra a equipe, desativa quem saiu e reativa quem voltou. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EquipeTest {

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
    private String tokenDoAdmin;

    @BeforeEach
    void cadastrarAdmin() throws Exception {
        jdbc.update("DELETE FROM usuarios");
        admin = repository.save(Usuario.daEquipe("Admin", "admin@academia.com", passwordEncoder.encode(SENHA), Perfil.ADMIN));
        tokenDoAdmin = token(login("admin@academia.com", SENHA));
    }

    private ResultActions login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, senha)));
    }

    private static String token(ResultActions resposta) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions criar(String token, String corpo) throws Exception {
        return mvc.perform(post("/api/usuarios").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private ResultActions acao(String token, UUID id, String acao) throws Exception {
        return mvc.perform(post("/api/usuarios/" + id + "/" + acao).header("Authorization", "Bearer " + token));
    }

    private Usuario daEquipe(String email, Perfil perfil) {
        return repository.save(Usuario.daEquipe(email, email, passwordEncoder.encode(SENHA), perfil));
    }

    @Test
    void adminCriaRecepcaoComSenhaTemporaria() throws Exception {
        String corpo = criar(tokenDoAdmin, """
                {"nome": "Rita", "email": "Rita@Academia.com", "perfil": "RECEPCAO"}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/usuarios/[0-9a-f-]{36}")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("rita@academia.com"))
                .andExpect(jsonPath("$.perfil").value("RECEPCAO"))
                .andReturn().getResponse().getContentAsString();
        String temporaria = JsonPath.read(corpo, "$.senhaTemporaria");

        login("rita@academia.com", temporaria)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaTrocarSenha").value(true));
    }

    @Test
    void adminPodeCriarOutroAdmin() throws Exception {
        criar(tokenDoAdmin, """
                {"nome": "Gerente", "email": "gerente@academia.com", "perfil": "ADMIN"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.perfil").value("ADMIN"));
    }

    @Test
    void perfilAlunoNaoEhCriadoPorAqui() throws Exception {
        criar(tokenDoAdmin, """
                {"nome": "Ana", "email": "ana@email.com", "perfil": "ALUNO"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "login de aluno é criado pelo cadastro do aluno: POST /api/alunos/{id}/login"));
    }

    @Test
    void dadosInvalidosDevolvem400() throws Exception {
        criar(tokenDoAdmin, """
                {"nome": "", "email": "nao-e-email", "perfil": null}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.length()").value(3));
        criar(tokenDoAdmin, """
                {"nome": "Rita", "email": "rita@academia.com", "perfil": "CHEFE"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailJaUsadoDevolve409() throws Exception {
        criar(tokenDoAdmin, """
                {"nome": "Outro", "email": "ADMIN@academia.com", "perfil": "RECEPCAO"}""")
                .andExpect(status().isConflict());
    }

    @Test
    void recepcaoNaoMexeNaEquipe() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        Usuario caio = daEquipe("caio@academia.com", Perfil.INSTRUTOR);
        String tokenDaRita = token(login("rita@academia.com", SENHA));

        criar(tokenDaRita, """
                {"nome": "X", "email": "x@academia.com", "perfil": "RECEPCAO"}""").andExpect(status().isForbidden());
        acao(tokenDaRita, caio.getId(), "desativar").andExpect(status().isForbidden());
        acao(tokenDaRita, rita.getId(), "reativar").andExpect(status().isForbidden());
        mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + tokenDaRita))
                .andExpect(status().isForbidden());
    }

    @Test
    void desativarDerrubaOAcessoEReativarDevolve() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        String sessaoDaRita = token(login("rita@academia.com", SENHA));

        acao(tokenDoAdmin, rita.getId(), "desativar")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + sessaoDaRita))
                .andExpect(status().isUnauthorized());
        login("rita@academia.com", SENHA).andExpect(status().isUnauthorized());

        acao(tokenDoAdmin, rita.getId(), "reativar")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(true));
        login("rita@academia.com", SENHA).andExpect(status().isOk());
    }

    @Test
    void reativarQuemJaEstaAtivoNaoDerrubaASessao() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        String sessaoDaRita = token(login("rita@academia.com", SENHA));

        acao(tokenDoAdmin, rita.getId(), "reativar").andExpect(status().isOk());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + sessaoDaRita)).andExpect(status().isOk());
    }

    @Test
    void ninguemDesativaASiMesmo() throws Exception {
        acao(tokenDoAdmin, admin.getId(), "desativar")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Você não pode desativar o seu próprio login"));
    }

    @Test
    void adminDesativaOutroAdmin() throws Exception {
        Usuario gerente = daEquipe("gerente@academia.com", Perfil.ADMIN);

        acao(tokenDoAdmin, gerente.getId(), "desativar").andExpect(status().isOk());
    }

    @Test
    void loginDeAlunoNaoEhDesativadoPorAqui() throws Exception {
        UUID alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Ana', '52998224725')", alunoId);
        Usuario loginDaAna = repository.save(Usuario.deAluno("Ana", "ana@email.com", "hash", alunoId));

        acao(tokenDoAdmin, loginDaAna.getId(), "desativar").andExpect(status().isBadRequest());
        acao(tokenDoAdmin, loginDaAna.getId(), "reativar").andExpect(status().isBadRequest());
        jdbc.update("DELETE FROM usuarios WHERE aluno_id = ?", alunoId);
        jdbc.update("DELETE FROM alunos WHERE id = ?", alunoId);
    }

    @Test
    void usuarioInexistenteDevolve404() throws Exception {
        acao(tokenDoAdmin, UUID.randomUUID(), "desativar").andExpect(status().isNotFound());
        acao(tokenDoAdmin, UUID.randomUUID(), "reativar").andExpect(status().isNotFound());
    }
}
