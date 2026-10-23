package br.com.academiagestao.alunos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
            jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_INSTRUTOR"));
    private static final RequestPostProcessor RECEPCAO =
            jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    AlunoRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private Aluno ana;

    @BeforeEach
    void cadastrar() {
        limpeza.apagarTudo();
        ana = repository.save(Aluno.novo(new DadosAluno("Ana", "52998224725", "ana@email.com", "31987654321",
                LocalDate.of(1995, 3, 10), "lesão no joelho esquerdo"), Instant.now()));
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

    /**
     * A lista só tem o resumo, para todos os perfis: os dados pessoais (inclusive as
     * observações, que podem ter dados de saúde) saem só pelo cadastro aberto, que vai para o
     * registro de acessos (diagnóstico da etapa 8, M1).
     */
    @Test
    void listaSoComOResumoParaTodos() throws Exception {
        RequestPostProcessor admin = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (RequestPostProcessor quem : List.of(INSTRUTOR, RECEPCAO, admin)) {
            String corpo = mvc.perform(get("/api/alunos").param("busca", "Ana").with(quem))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conteudo[0].id").value(ana.getId().toString()))
                    .andExpect(jsonPath("$.conteudo[0].nome").value("Ana"))
                    .andExpect(jsonPath("$.conteudo[0].dataNascimento").value("1995-03-10"))
                    .andExpect(jsonPath("$.conteudo[0].ativo").value(true))
                    .andReturn().getResponse().getContentAsString();
            Map<String, Object> primeiro = JsonPath.read(corpo, "$.conteudo[0]");
            assertThat(primeiro).containsOnlyKeys("id", "nome", "dataNascimento", "ativo");
        }
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
        // Na lista, não: só no cadastro aberto, que é registrado.
        mvc.perform(get("/api/alunos").with(RECEPCAO))
                .andExpect(jsonPath("$.conteudo[0].cpf").doesNotExist());
    }
}
