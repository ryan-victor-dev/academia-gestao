package br.com.academiagestao.treinos;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Catálogo de exercícios pela API (ADR 0027). As permissões por perfil estão em PermissoesDeExerciciosTest. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExercicioControllerTest {

    private static final RequestPostProcessor INSTRUTOR = jwt().authorities(new SimpleGrantedAuthority("ROLE_INSTRUTOR"));

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        limpeza.apagarTudo();
    }

    private static String json(String nome, String grupo, String observacao) {
        return "{\"nome\": \"%s\", \"grupoMuscular\": \"%s\", \"observacao\": %s}".formatted(nome, grupo,
                observacao == null ? "null" : "\"" + observacao + "\"");
    }

    private ResultActions cadastrar(String corpo) throws Exception {
        return mvc.perform(post("/api/exercicios").with(INSTRUTOR).contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private String cadastrarExercicio(String nome, String grupo) throws Exception {
        return JsonPath.read(cadastrar(json(nome, grupo, null)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions editar(String id, String ifMatch, String corpo) throws Exception {
        var requisicao = put("/api/exercicios/" + id).with(INSTRUTOR).contentType(MediaType.APPLICATION_JSON)
                .content(corpo);
        if (ifMatch != null) {
            requisicao.header("If-Match", ifMatch);
        }
        return mvc.perform(requisicao);
    }

    @Test
    void instrutorCadastraExercicio() throws Exception {
        cadastrar(json("  Supino   reto ", "PEITO", "Descer a barra até o peito"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/exercicios/[0-9a-f-]{36}")))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.nome").value("Supino reto"))
                .andExpect(jsonPath("$.grupoMuscular").value("PEITO"))
                .andExpect(jsonPath("$.observacao").value("Descer a barra até o peito"))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void nomeAtivoRepetidoDevolve409MesmoComAcentoOuMaiusculas() throws Exception {
        cadastrarExercicio("Supino", "PEITO");

        cadastrar(json("SUPÍNO", "OMBROS", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Nome de exercício em uso"));
    }

    @Test
    void dadosInvalidosDevolvem400ComOsCampos() throws Exception {
        cadastrar("{\"nome\": \" \", \"observacao\": \"%s\"}".formatted("x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[*].campo", hasItems("nome", "grupoMuscular", "observacao")));
        cadastrar(json("Supino", "PESCOCO", null)).andExpect(status().isBadRequest());
    }

    @Test
    void editarComEtagFuncionaSemEtag428ComEtagAntigo412() throws Exception {
        String id = cadastrarExercicio("Supino", "PEITO");

        editar(id, null, json("Supino reto", "PEITO", null)).andExpect(status().isPreconditionRequired());
        editar(id, "\"0\"", json("Supino reto", "PEITO", "Pés no chão"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.nome").value("Supino reto"))
                .andExpect(jsonPath("$.observacao").value("Pés no chão"));
        editar(id, "\"0\"", json("Supino", "PEITO", null)).andExpect(status().isPreconditionFailed());
    }

    @Test
    void renomearParaONomeDeOutroAtivoDevolve409() throws Exception {
        cadastrarExercicio("Supino", "PEITO");
        String remada = cadastrarExercicio("Remada", "COSTAS");

        editar(remada, "*", json("supino", "COSTAS", null)).andExpect(status().isConflict());
        // Mudar só o grupo, mantendo o próprio nome, é aceito.
        editar(remada, "*", json("Remada", "BICEPS", null)).andExpect(status().isOk());
    }

    @Test
    void inativoSaiDaListaPadraoEReativarComNomeTomadoDevolve409() throws Exception {
        String antigo = cadastrarExercicio("Supino", "PEITO");
        mvc.perform(post("/api/exercicios/" + antigo + "/inativar").with(INSTRUTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
        String novo = cadastrarExercicio("Supino", "PEITO");

        mvc.perform(get("/api/exercicios").with(INSTRUTOR))
                .andExpect(jsonPath("$[*].id", contains(novo)));
        mvc.perform(get("/api/exercicios?incluirInativos=true").with(INSTRUTOR))
                .andExpect(jsonPath("$[*].id", hasItems(novo, antigo)));
        mvc.perform(post("/api/exercicios/" + antigo + "/reativar").with(INSTRUTOR))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/exercicios/" + novo + "/inativar").with(INSTRUTOR)).andExpect(status().isOk());
        mvc.perform(post("/api/exercicios/" + antigo + "/reativar").with(INSTRUTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(true));
    }

    /** Corrigir um inativo cujo nome um ativo tomou é aceito: o nome só é único entre os ativos. */
    @Test
    void corrigirInativoComNomeTomadoEhAceito() throws Exception {
        String antigo = cadastrarExercicio("Supino", "PEITO");
        mvc.perform(post("/api/exercicios/" + antigo + "/inativar").with(INSTRUTOR)).andExpect(status().isOk());
        cadastrarExercicio("Supino", "PEITO");

        editar(antigo, "*", json("Supino", "PEITO", "Versão antiga")).andExpect(status().isOk())
                .andExpect(jsonPath("$.observacao").value("Versão antiga"))
                .andExpect(jsonPath("$.ativo").value(false));
    }

    @Test
    void filtraPorGrupoEmOrdemDeNome() throws Exception {
        String supino = cadastrarExercicio("Supino", "PEITO");
        String crucifixo = cadastrarExercicio("Crucifixo", "PEITO");
        cadastrarExercicio("Remada", "COSTAS");

        mvc.perform(get("/api/exercicios?grupoMuscular=PEITO").with(INSTRUTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(crucifixo, supino)));
    }

    @Test
    void exercicioInexistenteDevolve404() throws Exception {
        mvc.perform(get("/api/exercicios/" + UUID.randomUUID()).with(INSTRUTOR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Exercício não encontrado"));
        mvc.perform(post("/api/exercicios/" + UUID.randomUUID() + "/inativar").with(INSTRUTOR))
                .andExpect(status().isNotFound());
    }

    @Test
    void consultarDevolveOEtag() throws Exception {
        String id = cadastrarExercicio("Supino", "PEITO");

        mvc.perform(get("/api/exercicios/" + id).with(INSTRUTOR))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.nome").value("Supino"));
    }

    /** Toda alteração do catálogo vai para a trilha, na mesma transação (ADR 0021). */
    @Test
    void alteracoesVaoParaATrilha() throws Exception {
        String id = cadastrarExercicio("Supino", "PEITO");
        editar(id, "*", json("Supino reto", "PEITO", null)).andExpect(status().isOk());
        mvc.perform(post("/api/exercicios/" + id + "/inativar").with(INSTRUTOR)).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT acao FROM auditoria WHERE entidade = 'EXERCICIO' AND entidade_id = ?"
                + " ORDER BY momento, id", String.class, UUID.fromString(id)))
                .containsExactly("CRIADO", "EDITADO", "INATIVADO");
        assertThat(jdbc.queryForObject("SELECT alteracoes::text FROM auditoria WHERE entidade_id = ? AND acao = 'EDITADO'",
                String.class, UUID.fromString(id))).contains("Supino reto");
    }
}
