package br.com.academiagestao.planos;

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

import java.math.BigDecimal;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O aluno consulta os planos que pode contratar: só os ativos, numa versão resumida. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PlanosParaOAlunoTest {

    private static final RequestPostProcessor ALUNO = jwt().authorities(new SimpleGrantedAuthority("ROLE_ALUNO"));
    private static final RequestPostProcessor RECEPCAO = jwt().authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));

    @Autowired
    MockMvc mvc;

    @Autowired
    PlanoRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private Plano mensal;
    private Plano antigo;

    @BeforeEach
    void cadastrar() {
        jdbc.update("DELETE FROM planos");
        mensal = repository.save(Plano.novo(new DadosPlano("Mensal", new BigDecimal("129.90"), 1)));
        antigo = Plano.novo(new DadosPlano("Promoção 2025", new BigDecimal("79.90"), 1));
        antigo.inativar();
        antigo = repository.save(antigo);
    }

    @Test
    void alunoVeSoOsAtivosMesmoPedindoOsInativos() throws Exception {
        mvc.perform(get("/api/planos").param("incluirInativos", "true").with(ALUNO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nome").value(contains("Mensal")));
        // A recepção, com o mesmo pedido, vê os dois.
        mvc.perform(get("/api/planos").param("incluirInativos", "true").with(RECEPCAO))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void alunoRecebeAVersaoResumida() throws Exception {
        mvc.perform(get("/api/planos/" + mensal.getId()).with(ALUNO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Mensal"))
                .andExpect(jsonPath("$.valorMensal").value(129.90))
                .andExpect(jsonPath("$.valorTotal").value(129.90))
                .andExpect(jsonPath("$.ativo").doesNotExist())
                .andExpect(jsonPath("$.criadoEm").doesNotExist());
        mvc.perform(get("/api/planos").with(ALUNO))
                .andExpect(jsonPath("$[0].criadoEm").doesNotExist());
    }

    @Test
    void planoInativoNaoExisteParaOAluno() throws Exception {
        mvc.perform(get("/api/planos/" + antigo.getId()).with(ALUNO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Plano não encontrado"));
        mvc.perform(get("/api/planos/" + antigo.getId()).with(RECEPCAO))
                .andExpect(status().isOk());
    }
}
