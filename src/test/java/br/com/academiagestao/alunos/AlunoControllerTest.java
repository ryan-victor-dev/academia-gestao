package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AlunoControllerTest {

    private static final String CPF_ANA = "52998224725";
    private static final String CPF_BIA = "11144477735";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        jdbc.update("DELETE FROM usuarios WHERE aluno_id IS NOT NULL");
        jdbc.update("DELETE FROM alunos");
    }

    private static RequestPostProcessor perfil(String perfil) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("perfil", perfil))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private static String json(String nome, String cpf) {
        return """
                {"nome": "%s", "cpf": "%s", "email": "Ana@Email.com", "telefone": "(31) 98765-4321",
                 "dataNascimento": "1995-03-10"}
                """.formatted(nome, cpf);
    }

    private ResultActions cadastrar(String perfil, String corpo) throws Exception {
        return mvc.perform(post("/api/alunos").with(perfil(perfil))
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private String cadastrarAluno(String nome, String cpf) throws Exception {
        String corpo = cadastrar("RECEPCAO", json(nome, cpf))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    @Test
    void recepcaoCadastraAlunoEDevolve201ComLocation() throws Exception {
        cadastrar("RECEPCAO", json("Ana Souza", "529.982.247-25"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/alunos/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.cpf").value(CPF_ANA))
                .andExpect(jsonPath("$.email").value("ana@email.com"))
                .andExpect(jsonPath("$.telefone").value("31987654321"))
                .andExpect(jsonPath("$.ativo").value(true));
    }

    @Test
    void cpfInvalidoDevolve400ApontandoOCampo() throws Exception {
        cadastrar("RECEPCAO", json("Ana", "529.982.247-24"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[0].campo").value("cpf"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("CPF inválido"));
    }

    @Test
    void camposObrigatoriosFaltandoDevolvem400ComTodosOsCampos() throws Exception {
        cadastrar("RECEPCAO", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[*].campo").value(hasItem("nome")))
                .andExpect(jsonPath("$.erros[*].campo").value(hasItem("cpf")));
    }

    // Revisão de 2026-10-17: uma data que o banco não guarda respondia 409 "CPF já cadastrado".
    @ParameterizedTest
    @ValueSource(strings = {"-999999999-01-01", "-4714-01-01", "0001-01-01"})
    void nascimentoAbsurdoDevolve400EnaoCpfJaCadastrado(String data) throws Exception {
        cadastrar("RECEPCAO", """
                {"nome": "Ana", "cpf": "%s", "dataNascimento": "%s"}
                """.formatted(CPF_ANA, data))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("data de nascimento pode ser de no máximo 120 anos atrás"));
    }

    @Test
    void regraQueSoAEntidadeConfereTambemDevolve400() throws Exception {
        cadastrar("RECEPCAO", """
                {"nome": "Ana", "cpf": "%s", "telefone": "98765-4321"}
                """.formatted(CPF_ANA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("(10 ou 11 dígitos)")));
    }

    @Test
    void jsonMalformadoDevolve400() throws Exception {
        cadastrar("RECEPCAO", "{\"nome\": ")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void cpfRepetidoDevolve409() throws Exception {
        cadastrarAluno("Ana", CPF_ANA);

        cadastrar("ADMIN", json("Outra Ana", "529.982.247-25"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("CPF já cadastrado"));
    }

    @Test
    void instrutorConsultaMasNaoCadastra() throws Exception {
        String id = cadastrarAluno("Ana", CPF_ANA);

        mvc.perform(get("/api/alunos/" + id).with(perfil("INSTRUTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Ana"));
        cadastrar("INSTRUTOR", json("Bia", CPF_BIA))
                .andExpect(status().isForbidden());
    }

    @Test
    void alunoNaoConsultaCadastroPeloId() throws Exception {
        String id = cadastrarAluno("Ana", CPF_ANA);

        mvc.perform(get("/api/alunos/" + id).with(perfil("ALUNO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void idInexistenteDevolve404() throws Exception {
        mvc.perform(get("/api/alunos/" + UUID.randomUUID()).with(perfil("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Aluno não encontrado"));
    }

    @Test
    void edicaoSubstituiOsDados() throws Exception {
        String id = cadastrarAluno("Ana", CPF_ANA);

        mvc.perform(put("/api/alunos/" + id).with(perfil("RECEPCAO"))
                        .header("If-Match", "\"0\"") // versão de quem acabou de ser cadastrado
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nome": "Ana Maria Souza", "cpf": "%s"}
                                """.formatted(CPF_ANA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Ana Maria Souza"))
                .andExpect(jsonPath("$.email").isEmpty()); // não veio no PUT, então ficou vazio
    }

    @Test
    void edicaoParaCpfDeOutroAlunoDevolve409() throws Exception {
        cadastrarAluno("Ana", CPF_ANA);
        String idBia = cadastrarAluno("Bia", CPF_BIA);

        mvc.perform(put("/api/alunos/" + idBia).with(perfil("ADMIN"))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Bia", CPF_ANA)))
                .andExpect(status().isConflict());
    }
}
