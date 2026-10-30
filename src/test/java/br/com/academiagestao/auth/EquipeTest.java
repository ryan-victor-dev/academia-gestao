package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
    LimpezaDoBanco limpeza;

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
        limpeza.apagarTudo();
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

    private Usuario loginDeAluno(String nome, String email) {
        UUID alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email) VALUES (?, ?, ?, ?)", alunoId, nome,
                CpfsParaTeste.aleatorio(), email);
        return repository.save(Usuario.deAluno(nome, email, passwordEncoder.encode(SENHA), alunoId));
    }

    private ResultActions loginsDeAlunos(String parametros) throws Exception {
        return mvc.perform(get("/api/usuarios/alunos?" + parametros).header("Authorization", "Bearer " + tokenDoAdmin));
    }

    /** A lista da equipe não traz os logins de aluno, que podem ser milhares (diagnóstico de 27/10, B4). */
    @Test
    void listaDaEquipeSemOsLoginsDeAlunos() throws Exception {
        daEquipe("rita@academia.com", Perfil.RECEPCAO);
        loginDeAluno("Aluna", "aluna@academia.com");

        mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + tokenDoAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].email", containsInAnyOrder("admin@academia.com", "rita@academia.com")));
    }

    /** Os logins de aluno vêm paginados, pelo nome, com busca por parte do nome ou do e-mail. */
    @Test
    void loginsDeAlunosPaginadosComBusca() throws Exception {
        daEquipe("rita@academia.com", Perfil.RECEPCAO);
        loginDeAluno("Carla 100%", "carla@exemplo.com");
        loginDeAluno("Ana Souza", "ana@exemplo.com");
        loginDeAluno("Bruno Lima", "bruno.souza@outro.com");

        loginsDeAlunos("tamanho=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(3))
                .andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.conteudo[*].nome", contains("Ana Souza", "Bruno Lima")));
        loginsDeAlunos("tamanho=2&pagina=1").andExpect(jsonPath("$.conteudo[*].nome", contains("Carla 100%")));
        // "souza": no nome da Ana e no e-mail do Bruno; sem diferenciar maiúsculas.
        loginsDeAlunos("busca=SOUZA").andExpect(jsonPath("$.conteudo[*].nome", contains("Ana Souza", "Bruno Lima")));
        // "%" é texto, e não o curinga do LIKE.
        mvc.perform(get("/api/usuarios/alunos").param("busca", "%").header("Authorization", "Bearer " + tokenDoAdmin))
                .andExpect(jsonPath("$.conteudo[*].nome", contains("Carla 100%")));
        loginsDeAlunos("busca=rita").andExpect(jsonPath("$.totalElementos").value(0));
        loginsDeAlunos("tamanho=101").andExpect(status().isBadRequest());
        String tokenDaRita = token(login("rita@academia.com", SENHA));
        mvc.perform(get("/api/usuarios/alunos").header("Authorization", "Bearer " + tokenDaRita))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCriaRecepcaoComSenhaTemporaria() throws Exception {
        String corpo = criar(tokenDoAdmin, """
                {"nome": "Rita", "email": "Rita@Academia.com", "cpf": "516.879.548-01", "dataNascimento": "1990-05-10", "perfil": "RECEPCAO"}""")
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
                {"nome": "Gerente", "email": "gerente@academia.com", "cpf": "66906010484", "dataNascimento": "1990-05-10", "perfil": "ADMIN"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.perfil").value("ADMIN"));
    }

    @Test
    void perfilAlunoNaoEhCriadoPorAqui() throws Exception {
        criar(tokenDoAdmin, """
                {"nome": "Ana", "email": "ana@email.com", "cpf": "78059633805", "dataNascimento": "1990-05-10", "perfil": "ALUNO"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "login de aluno é criado pelo cadastro do aluno: POST /api/alunos/{id}/login"));
    }

    @Test
    void dadosInvalidosDevolvem400() throws Exception {
        criar(tokenDoAdmin, """
                {"nome": "", "email": "nao-e-email", "perfil": null}""")
                .andExpect(status().isBadRequest())
                // Nome, e-mail, CPF, data de nascimento (ADR 0048) e perfil.
                .andExpect(jsonPath("$.erros.length()").value(5));
        criar(tokenDoAdmin, """
                {"nome": "Rita", "email": "rita@academia.com", "cpf": "85730855923", "dataNascimento": "1990-05-10", "perfil": "CHEFE"}""")
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailJaUsadoDevolve409() throws Exception {
        criar(tokenDoAdmin, """
                {"nome": "Outro", "email": "ADMIN@academia.com", "cpf": "85730855923", "dataNascimento": "1990-05-10", "perfil": "RECEPCAO"}""")
                .andExpect(status().isConflict());
    }

    @Test
    void recepcaoNaoMexeNaEquipe() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        Usuario caio = daEquipe("caio@academia.com", Perfil.INSTRUTOR);
        String tokenDaRita = token(login("rita@academia.com", SENHA));

        criar(tokenDaRita, """
                {"nome": "X", "email": "x@academia.com", "cpf": "85730855923", "dataNascimento": "1990-05-10", "perfil": "RECEPCAO"}""").andExpect(status().isForbidden());
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
        // CPF aleatório: outros testes, no mesmo banco, podem ter deixado alunos com CPFs fixos.
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Ana', ?)", alunoId, CpfsParaTeste.aleatorio());
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
