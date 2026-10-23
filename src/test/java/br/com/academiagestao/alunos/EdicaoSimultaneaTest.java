package br.com.academiagestao.alunos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Duas recepcionistas editando o mesmo aluno: a segunda não pode apagar o que a primeira fez. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EdicaoSimultaneaTest {

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
        ana = repository.save(Aluno.novo(new DadosAluno("Ana", "52998224725", null, null, null, null), Instant.now()));
    }

    private String etagDoGet() throws Exception {
        return mvc.perform(get("/api/alunos/" + ana.getId()).with(RECEPCAO))
                .andReturn().getResponse().getHeader("ETag");
    }

    private ResultActions editar(String ifMatch, String nome) throws Exception {
        var requisicao = put("/api/alunos/" + ana.getId()).with(RECEPCAO)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"%s\", \"cpf\": \"52998224725\"}".formatted(nome));
        if (ifMatch != null) {
            requisicao.header("If-Match", ifMatch);
        }
        return mvc.perform(requisicao);
    }

    private String nomeNoBanco() {
        return repository.findById(ana.getId()).orElseThrow().getNome();
    }

    @Test
    void getDevolveEtagComAVersao() throws Exception {
        mvc.perform(get("/api/alunos/" + ana.getId()).with(RECEPCAO))
                .andExpect(header().string("ETag", "\"0\""));
    }

    @Test
    void edicaoComEtagAtualFuncionaEDevolveONovoEtag() throws Exception {
        editar(etagDoGet(), "Ana Maria")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.nome").value("Ana Maria"));
    }

    @Test
    void segundaEdicaoComEtagAntigoEhRecusadaENaoApagaAPrimeira() throws Exception {
        String etagDaRita = etagDoGet();   // Rita abre a tela do aluno
        String etagDaJulia = etagDoGet();  // Júlia abre a mesma tela

        editar(etagDaRita, "Ana Maria").andExpect(status().isOk());

        editar(etagDaJulia, "Ana Souza")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.title").value("Aluno alterado por outra pessoa"));
        assertThat(nomeNoBanco()).isEqualTo("Ana Maria");
    }

    @Test
    void semIfMatchDevolve428ENaoAltera() throws Exception {
        editar(null, "Ana Maria")
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.title").value("Cabeçalho If-Match obrigatório"));
        assertThat(nomeNoBanco()).isEqualTo("Ana");
    }

    @Test
    void curingaSobrescreveDeProposito() throws Exception {
        editar("*", "Ana Maria").andExpect(status().isOk());
        assertThat(nomeNoBanco()).isEqualTo("Ana Maria");
    }

    @Test
    void inativarTambemMudaAVersao() throws Exception {
        String etagAntigo = etagDoGet();

        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO))
                .andExpect(header().string("ETag", "\"1\""));

        editar(etagAntigo, "Ana Maria").andExpect(status().isPreconditionFailed());
    }

    @Test
    void bancoRecusaGravarUmaVersaoDesatualizada() {
        // Prova que o @Version está mapeado: gravar uma cópia antiga do aluno falha.
        Aluno copiaAntiga = repository.findById(ana.getId()).orElseThrow();
        jdbc.update("UPDATE alunos SET nome = 'Outra pessoa mudou', versao = versao + 1 WHERE id = ?", ana.getId());

        copiaAntiga.atualizar(new DadosAluno("Ana Maria", "52998224725", null, null, null, null), Instant.now());
        assertThatThrownBy(() -> repository.saveAndFlush(copiaAntiga))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
