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

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O instrutor vê só o que precisa para montar o treino; a recepção vê o cadastro completo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class VisaoDoInstrutorTest {

    private static final RequestPostProcessor INSTRUTOR =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_INSTRUTOR"));
    private static final RequestPostProcessor RECEPCAO =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));

    @Autowired
    MockMvc mvc;

    @Autowired
    AlunoRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private Aluno ana;

    @BeforeEach
    void cadastrar() {
        jdbc.update("DELETE FROM usuarios");
        repository.deleteAll();
        ana = repository.save(Aluno.novo(new DadosAluno("Ana", "52998224725", "ana@email.com", "31987654321",
                LocalDate.of(1995, 3, 10), "lesão no joelho esquerdo")));
    }

    @Test
    void instrutorConsultaSemCpfEmailETelefone() throws Exception {
        mvc.perform(get("/api/alunos/" + ana.getId()).with(INSTRUTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Ana"))
                .andExpect(jsonPath("$.dataNascimento").value("1995-03-10"))
                .andExpect(jsonPath("$.observacoes").value("lesão no joelho esquerdo"))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.cpf").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.telefone").doesNotExist());
    }

    @Test
    void instrutorListaSemCpfEmailETelefone() throws Exception {
        mvc.perform(get("/api/alunos").with(INSTRUTOR))
                .andExpect(jsonPath("$.conteudo[0].nome").value("Ana"))
                .andExpect(jsonPath("$.conteudo[0].observacoes").value("lesão no joelho esquerdo"))
                .andExpect(jsonPath("$.conteudo[0].cpf").doesNotExist())
                .andExpect(jsonPath("$.conteudo[0].email").doesNotExist())
                .andExpect(jsonPath("$.conteudo[0].telefone").doesNotExist());
    }

    @Test
    void instrutorNaoDescobreDeQuemEUmCpfPelaBusca() throws Exception {
        mvc.perform(get("/api/alunos").param("busca", "529.982").with(INSTRUTOR))
                .andExpect(jsonPath("$.totalElementos").value(0));
        // A mesma busca, feita pela recepção, encontra a Ana.
        mvc.perform(get("/api/alunos").param("busca", "529.982").with(RECEPCAO))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test
    void instrutorContinuaBuscandoPorNome() throws Exception {
        mvc.perform(get("/api/alunos").param("busca", "ana").with(INSTRUTOR))
                .andExpect(jsonPath("$.conteudo[0].nome").value("Ana"));
    }

    @Test
    void recepcaoContinuaVendoOCadastroCompleto() throws Exception {
        mvc.perform(get("/api/alunos/" + ana.getId()).with(RECEPCAO))
                .andExpect(jsonPath("$.cpf").value("52998224725"))
                .andExpect(jsonPath("$.email").value("ana@email.com"))
                .andExpect(jsonPath("$.telefone").value("31987654321"));
        mvc.perform(get("/api/alunos").with(RECEPCAO))
                .andExpect(jsonPath("$.conteudo[0].cpf").value("52998224725"));
    }
}
