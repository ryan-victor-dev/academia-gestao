package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AlunoListagemTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AlunoRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private Aluno carla;

    @BeforeEach
    void cadastrar() {
        jdbc.update("DELETE FROM usuarios WHERE aluno_id IS NOT NULL");
        repository.deleteAll();
        repository.save(Aluno.novo(new DadosAluno("Bruno Lima", "11144477735", null, null, null, null)));
        repository.save(Aluno.novo(new DadosAluno("Ana Souza", "52998224725", null, null, null, null)));
        carla = Aluno.novo(new DadosAluno("Carla Anastácio", "93541134780", null, null, null, null));
        carla.inativar();
        carla = repository.save(carla);
    }

    private static RequestPostProcessor perfil(String perfil) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("perfil", perfil))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    @Test
    void listaSoAtivosEmOrdemDeNomePorPadrao() throws Exception {
        mvc.perform(get("/api/alunos").with(perfil("INSTRUTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo[*].nome").value(contains("Ana Souza", "Bruno Lima")))
                .andExpect(jsonPath("$.totalElementos").value(2));
    }

    @Test
    void incluirInativosTrazTodos() throws Exception {
        mvc.perform(get("/api/alunos").param("incluirInativos", "true").with(perfil("RECEPCAO")))
                .andExpect(jsonPath("$.conteudo[*].nome").value(contains("Ana Souza", "Bruno Lima", "Carla Anastácio")));
    }

    @Test
    void buscaPorParteDoNomeSemDiferenciarMaiusculas() throws Exception {
        // "ana" está em "Ana Souza" e em "Carla Anastácio"; a Carla está inativa.
        mvc.perform(get("/api/alunos").param("busca", "ANA").param("incluirInativos", "true").with(perfil("ADMIN")))
                .andExpect(jsonPath("$.conteudo[*].nome").value(contains("Ana Souza", "Carla Anastácio")));
    }

    @Test
    void buscaPeloComecoDoCpfComPontuacao() throws Exception {
        mvc.perform(get("/api/alunos").param("busca", "529.982").with(perfil("ADMIN")))
                .andExpect(jsonPath("$.conteudo[*].nome").value(contains("Ana Souza")));
    }

    @Test
    void curingaDoLikeNaoTrazTodosOsAlunos() throws Exception {
        mvc.perform(get("/api/alunos").param("busca", "%").with(perfil("ADMIN")))
                .andExpect(jsonPath("$.totalElementos").value(0));
    }

    @Test
    void paginacao() throws Exception {
        mvc.perform(get("/api/alunos").param("tamanho", "1").param("pagina", "1").with(perfil("ADMIN")))
                .andExpect(jsonPath("$.conteudo[*].nome").value(contains("Bruno Lima")))
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.tamanho").value(1))
                .andExpect(jsonPath("$.totalElementos").value(2))
                .andExpect(jsonPath("$.totalPaginas").value(2));
    }

    @Test
    void tamanhoDePaginaAcimaDoLimiteDevolve400() throws Exception {
        mvc.perform(get("/api/alunos").param("tamanho", "500").with(perfil("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("tamanho"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("pode ser no máximo 100"));
    }

    @Test
    void alunoNaoLista() throws Exception {
        mvc.perform(get("/api/alunos").with(perfil("ALUNO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void inativarEReativar() throws Exception {
        mvc.perform(post("/api/alunos/" + carla.getId() + "/reativar").with(perfil("RECEPCAO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(true));
        mvc.perform(get("/api/alunos").with(perfil("RECEPCAO")))
                .andExpect(jsonPath("$.totalElementos").value(3));

        mvc.perform(post("/api/alunos/" + carla.getId() + "/inativar").with(perfil("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
        mvc.perform(get("/api/alunos").with(perfil("RECEPCAO")))
                .andExpect(jsonPath("$.totalElementos").value(2));
    }

    @Test
    void instrutorNaoInativa() throws Exception {
        mvc.perform(post("/api/alunos/" + carla.getId() + "/inativar").with(perfil("INSTRUTOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void inativarAlunoInexistenteDevolve404() throws Exception {
        mvc.perform(post("/api/alunos/" + UUID.randomUUID() + "/inativar").with(perfil("ADMIN")))
                .andExpect(status().isNotFound());
    }
}
