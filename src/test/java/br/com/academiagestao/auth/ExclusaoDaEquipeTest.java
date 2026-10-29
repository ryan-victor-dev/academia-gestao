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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excluir o login da equipe (ADR 0043): só o que nunca foi usado sai. Quem já entrou, é autor
 * na trilha ou registrou algo fica, e o caminho é desativar. Com tokens de verdade.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExclusaoDaEquipeTest {

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
        jdbc.update("DELETE FROM acessos");
        jdbc.update("DELETE FROM auditoria");
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

    private Usuario daEquipe(String email, Perfil perfil) {
        return repository.save(Usuario.daEquipe(email, email, passwordEncoder.encode(SENHA), perfil));
    }

    private ResultActions excluir(String token, UUID id) throws Exception {
        return mvc.perform(delete("/api/usuarios/" + id).header("Authorization", "Bearer " + token));
    }

    private boolean existe(UUID id) {
        return repository.existsById(id);
    }

    @Test
    void loginNuncaUsadoEhExcluidoEATrilhaGuardaOUltimoRetrato() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);

        excluir(tokenDoAdmin, rita.getId()).andExpect(status().isNoContent());

        assertThat(existe(rita.getId())).isFalse();
        String excluido = jdbc.queryForObject("SELECT alteracoes::text FROM auditoria WHERE entidade = 'USUARIO'"
                + " AND entidade_id = ? AND acao = 'EXCLUIDO'", String.class, rita.getId());
        assertThat(JsonPath.<String>read(excluido, "$.email.antes")).isEqualTo("rita@academia.com");
        assertThat(JsonPath.<Object>read(excluido, "$.email.depois")).isNull();
        mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + tokenDoAdmin))
                .andExpect(jsonPath("$[*].email").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("rita@academia.com"))));
    }

    @Test
    void adminNuncaUsadoTambemSai() throws Exception {
        Usuario gerente = daEquipe("gerente@academia.com", Perfil.ADMIN);

        excluir(tokenDoAdmin, gerente.getId()).andExpect(status().isNoContent());
        assertThat(existe(gerente.getId())).isFalse();
    }

    @Test
    void quemJaEntrouNaoEhExcluido() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        login("rita@academia.com", SENHA).andExpect(status().isOk());

        excluir(tokenDoAdmin, rita.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Usuário com histórico"))
                .andExpect(jsonPath("$.detail").value(containsString("desative")));
        assertThat(existe(rita.getId())).isTrue();
    }

    /** Uma senha errada não é uso: qualquer um que tentasse adivinhar impediria a exclusão. */
    @Test
    void senhaErradaNaoContaComoUso() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        login("rita@academia.com", "senha-errada-123").andExpect(status().isUnauthorized());

        excluir(tokenDoAdmin, rita.getId()).andExpect(status().isNoContent());
    }

    /** Ficha montada pelo instrutor: a chave estrangeira barra, mesmo sem ele ter entrado. */
    @Test
    void quemRegistrouAlgoNaoEhExcluido() throws Exception {
        Usuario caio = daEquipe("caio@academia.com", Perfil.INSTRUTOR);
        UUID aluno = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO fichas (id, aluno_id, montada_em, montada_por) VALUES (gen_random_uuid(), ?, now(), ?)",
                aluno, caio.getId());

        excluir(tokenDoAdmin, caio.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Usuário com histórico"));
        assertThat(existe(caio.getId())).isTrue();
    }

    @Test
    void autorNaTrilhaNaoEhExcluido() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        jdbc.update("INSERT INTO auditoria (autor, entidade, entidade_id, acao, alteracoes) VALUES (?, 'PLANO', ?, 'TESTE', '{}')",
                rita.getId().toString(), UUID.randomUUID());

        excluir(tokenDoAdmin, rita.getId()).andExpect(status().isConflict());
        assertThat(existe(rita.getId())).isTrue();
    }

    @Test
    void ninguemExcluiASiMesmo() throws Exception {
        excluir(tokenDoAdmin, admin.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Você não pode excluir o seu próprio login"));
        assertThat(existe(admin.getId())).isTrue();
    }

    @Test
    void loginDeAlunoNaoEhExcluidoPorAqui() throws Exception {
        UUID alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Ana', ?)", alunoId, CpfsParaTeste.aleatorio());
        Usuario loginDaAna = repository.save(Usuario.deAluno("Ana", "ana@email.com", "hash", alunoId));

        excluir(tokenDoAdmin, loginDaAna.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("exclua o aluno")));
        assertThat(existe(loginDaAna.getId())).isTrue();
    }

    @Test
    void soOAdminExclui() throws Exception {
        Usuario rita = daEquipe("rita@academia.com", Perfil.RECEPCAO);
        Usuario caio = daEquipe("caio@academia.com", Perfil.INSTRUTOR);
        String tokenDaRita = token(login("rita@academia.com", SENHA));

        excluir(tokenDaRita, caio.getId()).andExpect(status().isForbidden());
        mvc.perform(delete("/api/usuarios/" + caio.getId())).andExpect(status().isUnauthorized());
        assertThat(existe(caio.getId())).isTrue();
    }

    @Test
    void usuarioInexistenteDevolve404() throws Exception {
        excluir(tokenDoAdmin, UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
