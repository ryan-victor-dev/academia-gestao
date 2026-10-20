package br.com.academiagestao.cobranca;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A configuração de atraso da academia (ADR 0024): a recepção lê, só o ADMIN altera. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConfiguracaoDaCobrancaTest {

    private static final String URL = "/api/cobrancas/configuracao";
    private static final String R_7_REAIS = """
            {"tipoAcrescimo": "VALOR", "acrescimo": 7, "jurosAoMes": 1.5, "diasParaBloquear": 5}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final String admin = UUID.randomUUID().toString();

    private static RequestPostProcessor perfil(String perfil, String id) {
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private static RequestPostProcessor perfil(String perfil) {
        return perfil(perfil, UUID.randomUUID().toString());
    }

    /** Volta aos valores do dono: os outros testes contam com eles. */
    @AfterEach
    void restaurar() {
        jdbc.update("UPDATE configuracao_cobranca SET tipo_acrescimo = 'PERCENTUAL', acrescimo = 2, juros_ao_mes = 1, "
                + "dias_para_bloquear = 10");
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'CONFIGURACAO'");
    }

    private String etag() throws Exception {
        return mvc.perform(get(URL).with(perfil("ADMIN"))).andReturn().getResponse().getHeader("ETag");
    }

    private ResultActions alterar(String corpo, String ifMatch, RequestPostProcessor quem) throws Exception {
        var requisicao = put(URL).with(quem).contentType(MediaType.APPLICATION_JSON).content(corpo);
        return mvc.perform(ifMatch == null ? requisicao : requisicao.header("If-Match", ifMatch));
    }

    @Test
    void comecaComOsValoresDoDono() throws Exception {
        mvc.perform(get(URL).with(perfil("RECEPCAO")))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.tipoAcrescimo").value("PERCENTUAL"))
                .andExpect(jsonPath("$.acrescimo").value(2.0))
                .andExpect(jsonPath("$.jurosAoMes").value(1.0))
                .andExpect(jsonPath("$.diasParaBloquear").value(10))
                .andExpect(jsonPath("$.tetoDoAcrescimoPercentual").value(2));
    }

    @Test
    void adminAlteraEAMudancaVaiParaATrilha() throws Exception {
        String antes = etag();

        alterar(R_7_REAIS, antes, perfil("ADMIN", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoAcrescimo").value("VALOR"))
                .andExpect(jsonPath("$.acrescimo").value(7.0))
                .andExpect(jsonPath("$.jurosAoMes").value(1.5))
                .andExpect(jsonPath("$.diasParaBloquear").value(5));

        assertThat(etag()).isNotEqualTo(antes);
        mvc.perform(get(URL).with(perfil("RECEPCAO"))).andExpect(jsonPath("$.acrescimo").value(7.0));
        var trilha = jdbc.queryForMap("SELECT autor, entidade_id, acao, alteracoes::text AS alteracoes FROM auditoria "
                + "WHERE entidade = 'CONFIGURACAO'");
        assertThat(trilha.get("autor")).isEqualTo(admin);
        assertThat(trilha.get("entidade_id")).isEqualTo(ConfiguracaoDaCobrancaService.ID_NA_TRILHA);
        assertThat(trilha.get("acao")).isEqualTo("EDITADA");
        assertThat((String) trilha.get("alteracoes")).contains("\"acrescimo\": {\"antes\": 2.00, \"depois\": 7.00}")
                .contains("\"tipoAcrescimo\": {\"antes\": \"PERCENTUAL\", \"depois\": \"VALOR\"}")
                .contains("\"diasParaBloquear\": {\"antes\": 10, \"depois\": 5}");
    }

    @Test
    void semIfMatch428EComEtagVelho412() throws Exception {
        String velho = etag();
        alterar(R_7_REAIS, null, perfil("ADMIN")).andExpect(status().isPreconditionRequired());
        alterar(R_7_REAIS, velho, perfil("ADMIN")).andExpect(status().isOk());
        alterar(R_7_REAIS, velho, perfil("ADMIN")).andExpect(status().isPreconditionFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEPCAO", "INSTRUTOR", "ALUNO"})
    void soOAdminAltera(String perfil) throws Exception {
        alterar(R_7_REAIS, "*", perfil(perfil)).andExpect(status().isForbidden());
        mvc.perform(get(URL).with(perfil("RECEPCAO"))).andExpect(jsonPath("$.tipoAcrescimo").value("PERCENTUAL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTRUTOR", "ALUNO"})
    void instrutorEAlunoNaoVeem(String perfil) throws Exception {
        mvc.perform(get(URL).with(perfil(perfil))).andExpect(status().isForbidden());
    }

    @Test
    void semTokenNaoVe() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"tipoAcrescimo\": \"PERCENTUAL\", \"acrescimo\": 2.01, \"jurosAoMes\": 1, \"diasParaBloquear\": 10}",
            "{\"tipoAcrescimo\": \"VALOR\", \"acrescimo\": 1000.01, \"jurosAoMes\": 1, \"diasParaBloquear\": 10}",
            "{\"tipoAcrescimo\": \"VALOR\", \"acrescimo\": 7, \"jurosAoMes\": 10.01, \"diasParaBloquear\": 10}",
            "{\"tipoAcrescimo\": \"VALOR\", \"acrescimo\": 7, \"jurosAoMes\": 1, \"diasParaBloquear\": 91}",
            "{\"tipoAcrescimo\": \"VALOR\", \"acrescimo\": 7, \"jurosAoMes\": 1}",
            "{\"tipoAcrescimo\": \"OUTRO\", \"acrescimo\": 7, \"jurosAoMes\": 1, \"diasParaBloquear\": 10}"})
    void foraDasFaixas400(String corpo) throws Exception {
        alterar(corpo, "*", perfil("ADMIN")).andExpect(status().isBadRequest());
        mvc.perform(get(URL).with(perfil("RECEPCAO"))).andExpect(jsonPath("$.tipoAcrescimo").value("PERCENTUAL"));
    }

    @Test
    void percentualAcimaDe2ExplicaOTeto() throws Exception {
        alterar("{\"tipoAcrescimo\": \"PERCENTUAL\", \"acrescimo\": 5, \"jurosAoMes\": 1, \"diasParaBloquear\": 10}",
                "*", perfil("ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("0 a 2%")));
    }

    /** O banco também recusa (última garantia, para uma alteração feita fora da aplicação). */
    @ParameterizedTest
    @ValueSource(strings = {
            "tipo_acrescimo = 'PERCENTUAL', acrescimo = 2.01",
            "tipo_acrescimo = 'VALOR', acrescimo = 1000.01",
            "acrescimo = -1",
            "juros_ao_mes = 10.01",
            "juros_ao_mes = -0.01",
            "dias_para_bloquear = 91",
            "dias_para_bloquear = -1",
            "tipo_acrescimo = 'OUTRO'"})
    void bancoRecusaForaDasFaixas(String alteracao) {
        assertThatThrownBy(() -> jdbc.update("UPDATE configuracao_cobranca SET " + alteracao))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bancoTemUmaConfiguracaoSo() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO configuracao_cobranca "
                + "(id, tipo_acrescimo, acrescimo, juros_ao_mes, dias_para_bloquear) VALUES (2, 'VALOR', 7, 1, 10)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Dois ADMINs alteram ao mesmo tempo com o mesmo ETag: a linha travada faz o segundo ver a
     * versão nova e receber 412, em vez de sobrescrever o primeiro sem saber. Várias rodadas.
     */
    @Test
    void alteracoesSimultaneasNaoSeAtropelam() throws Exception {
        java.util.Set<Integer> vencedores = new java.util.HashSet<>();
        for (int rodada = 0; rodada < 20; rodada++) {
            String etag = etag();
            CountDownLatch largada = new CountDownLatch(1);
            List<CompletableFuture<Integer>> lados = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int dias = 20 + i;
                lados.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        largada.await();
                        return alterar("{\"tipoAcrescimo\": \"VALOR\", \"acrescimo\": 7, \"jurosAoMes\": 1, "
                                + "\"diasParaBloquear\": " + dias + "}", etag, perfil("ADMIN"))
                                .andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        return -1;
                    }
                }));
            }
            largada.countDown();
            List<Integer> resultados = lados.stream().map(CompletableFuture::join).sorted().toList();
            assertThat(resultados).as("rodada %d", rodada).containsExactly(200, 412);
            vencedores.add(jdbc.queryForObject("SELECT dias_para_bloquear FROM configuracao_cobranca", Integer.class));
        }
        // Se um lado ganhasse sempre, a corrida não teria sido testada.
        assertThat(vencedores).containsExactlyInAnyOrder(20, 21);
    }
}
