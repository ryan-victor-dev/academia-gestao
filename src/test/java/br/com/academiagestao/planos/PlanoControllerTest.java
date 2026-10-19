package br.com.academiagestao.planos;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PlanoControllerTest {

    private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

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

    private static String json(String nome, String valor, int meses) {
        return "{\"nome\": \"%s\", \"valorMensal\": %s, \"duracaoMeses\": %d}".formatted(nome, valor, meses);
    }

    private ResultActions cadastrar(String corpo) throws Exception {
        return mvc.perform(post("/api/planos").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private String cadastrarPlano(String nome) throws Exception {
        return JsonPath.read(cadastrar(json(nome, "100", 1)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions editar(String id, String ifMatch, String corpo) throws Exception {
        var requisicao = put("/api/planos/" + id).with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(corpo);
        if (ifMatch != null) {
            requisicao.header("If-Match", ifMatch);
        }
        return mvc.perform(requisicao);
    }

    @Test
    void adminCadastraPlanoComValorTotal() throws Exception {
        cadastrar(json("  Trimestral ", "119.9", 3))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/planos/[0-9a-f-]{36}")))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.nome").value("Trimestral"))
                .andExpect(jsonPath("$.valorMensal").value(119.90))
                .andExpect(jsonPath("$.duracaoMeses").value(3))
                .andExpect(jsonPath("$.valorTotal").value(359.70))
                .andExpect(jsonPath("$.ativo").value(true));
    }

    @Test
    void valorEmJsonSaiComDuasCasas() throws Exception {
        String corpo = cadastrar(json("Mensal", "129.9", 1)).andReturn().getResponse().getContentAsString();

        // Dinheiro sem perder os centavos na exibição: 129.90, e não 129.9.
        assertThat(corpo).contains("\"valorMensal\":129.90");
    }

    @Test
    void nomeAtivoRepetidoDevolve409MesmoComAcentoOuMaiusculas() throws Exception {
        cadastrarPlano("Mensal");

        cadastrar(json("MÊNSAL", "90", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Nome de plano em uso"));
    }

    @Test
    void dadosInvalidosDevolvem400ComOsCampos() throws Exception {
        cadastrar("{\"nome\": \"\", \"valorMensal\": 129.999, \"duracaoMeses\": 61}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.erros[*].campo").value(hasItems("nome", "valorMensal", "duracaoMeses")));
        cadastrar("{\"nome\": \"X\", \"valorMensal\": -1, \"duracaoMeses\": 0}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void editarComEtagFuncionaSemEtag428ComEtagAntigo412() throws Exception {
        String id = cadastrarPlano("Mensal");

        editar(id, "\"0\"", json("Mensal", "139.90", 1))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.valorMensal").value(139.90));
        editar(id, null, json("Mensal", "149.90", 1))
                .andExpect(status().isPreconditionRequired());
        editar(id, "\"0\"", json("Mensal", "149.90", 1))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.title").value("Plano alterado por outra pessoa"));
    }

    @Test
    void renomearParaONomeDeOutroAtivoDevolve409() throws Exception {
        cadastrarPlano("Mensal");
        String anual = cadastrarPlano("Anual");

        editar(anual, "\"0\"", json("mensal", "100", 1)).andExpect(status().isConflict());
        // Editar o próprio plano sem trocar o nome não conflita consigo mesmo.
        editar(anual, "\"0\"", json("Anual", "90", 12)).andExpect(status().isOk());
    }

    @Test
    void inativoSaiDaListaPadraoEReativarComNomeTomadoDevolve409() throws Exception {
        String antigo = cadastrarPlano("Mensal");
        mvc.perform(post("/api/planos/" + antigo + "/inativar").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
        cadastrarPlano("Mensal"); // o nome ficou livre

        mvc.perform(get("/api/planos").with(ADMIN))
                .andExpect(jsonPath("$[*].ativo").value(contains(true)));
        mvc.perform(get("/api/planos").param("incluirInativos", "true").with(ADMIN))
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(post("/api/planos/" + antigo + "/reativar").with(ADMIN))
                .andExpect(status().isConflict());
    }

    @Test
    void reativarSemConflitoFunciona() throws Exception {
        String id = cadastrarPlano("Mensal");
        mvc.perform(post("/api/planos/" + id + "/inativar").with(ADMIN));

        mvc.perform(post("/api/planos/" + id + "/reativar").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(true));
    }

    @Test
    void listaEmOrdemDeNome() throws Exception {
        cadastrarPlano("Trimestral");
        cadastrarPlano("Anual");
        cadastrarPlano("Mensal");

        mvc.perform(get("/api/planos").with(ADMIN))
                .andExpect(jsonPath("$[*].nome").value(contains("Anual", "Mensal", "Trimestral")));
    }

    @Test
    void planoInexistenteDevolve404() throws Exception {
        mvc.perform(get("/api/planos/" + UUID.randomUUID()).with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Plano não encontrado"));
    }

    // Fidelidade e forma de pagamento (ADR 0018)

    @Test
    void cadastraPlanoComFidelidadeEPagamentoAVista() throws Exception {
        cadastrar("""
                {"nome": "Anual", "valorMensal": 99.90, "duracaoMeses": 12,
                 "fidelidade": true, "percentualMulta": 12.5, "formaPagamento": "A_VISTA"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fidelidade").value(true))
                .andExpect(jsonPath("$.percentualMulta").value(12.50))
                .andExpect(jsonPath("$.formaPagamento").value("A_VISTA"));
    }

    @Test
    void semOsCamposNovosFicaSemFidelidadeEMensal() throws Exception {
        cadastrar(json("Mensal", "129.90", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fidelidade").value(false))
                .andExpect(jsonPath("$.percentualMulta").doesNotExist())
                .andExpect(jsonPath("$.formaPagamento").value("MENSAL"));
    }

    @Test
    void regrasDeFidelidadeInvalidasDao400() throws Exception {
        cadastrar("{\"nome\": \"A\", \"valorMensal\": 100, \"duracaoMeses\": 12, \"fidelidade\": true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("percentual")));
        cadastrar("{\"nome\": \"B\", \"valorMensal\": 100, \"duracaoMeses\": 12, \"percentualMulta\": 20}")
                .andExpect(status().isBadRequest());
        cadastrar("{\"nome\": \"C\", \"valorMensal\": 100, \"duracaoMeses\": 12, \"fidelidade\": true,"
                + " \"percentualMulta\": 12.345}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("percentualMulta"));
        cadastrar("{\"nome\": \"D\", \"valorMensal\": 100, \"duracaoMeses\": 12, \"formaPagamento\": \"BOLETO\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void alunoVeAFidelidadeEAMultaAntesDeContratar() throws Exception {
        String id = JsonPath.read(cadastrar("""
                {"nome": "Anual", "valorMensal": 99.90, "duracaoMeses": 12,
                 "fidelidade": true, "percentualMulta": 20}""")
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/planos/" + id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ALUNO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fidelidade").value(true))
                .andExpect(jsonPath("$.percentualMulta").value(20.00))
                .andExpect(jsonPath("$.formaPagamento").value("MENSAL"));
    }
}
