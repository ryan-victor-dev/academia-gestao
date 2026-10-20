package br.com.academiagestao.cobranca;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consultar, pagar, estornar e cancelar cobranças pela API (ADR 0024). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CobrancaControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    private final UUID aluno = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID matricula = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID recepcao = UUID.randomUUID();
    private final UUID outraRecepcao = UUID.randomUUID();
    private LocalDate hoje;

    @BeforeEach
    void preparar() {
        // Amanhã ao meio-dia em São Paulo: "hoje" fixo no futuro, sem depender da data real.
        hoje = Calendario.hoje(Instant.now()).plusDays(1);
        relogio.fixar(hoje.atTime(12, 0).atZone(Calendario.FUSO).toInstant());
        for (UUID usuario : List.of(admin, recepcao, outraRecepcao)) {
            jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'U', ?, 'x', ?)",
                    usuario, usuario + "@teste.local", usuario.equals(admin) ? "ADMIN" : "RECEPCAO");
        }
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 3)", plano, "P " + plano);
        LocalDate inicio = hoje.minusDays(30);
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'P', 100, 3, ?, ?, now())""", matricula, aluno, plano, inicio, inicio.plusMonths(3).minusDays(1));
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'COBRANCA' AND entidade_id IN "
                + "(SELECT id FROM cobrancas WHERE aluno_id = ?)", aluno);
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("planos", plano);
        for (UUID usuario : List.of(admin, recepcao, outraRecepcao)) {
            limpeza.apagar("usuarios", usuario);
        }
        jdbc.update("UPDATE configuracao_cobranca SET tipo_acrescimo = 'PERCENTUAL', acrescimo = 2, juros_ao_mes = 1, "
                + "dias_para_bloquear = 10");
    }

    /** Uma cobrança de 100,00 vencendo {@code diasAtras} dias antes de hoje. */
    private UUID cobranca(int parcela, int diasAtras) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, ?, 'MENSALIDADE', ?, 100, ?, 'PENDENTE')""", id, matricula, aluno, parcela,
                hoje.minusDays(diasAtras));
        return id;
    }

    private static RequestPostProcessor como(String perfil, UUID id) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private RequestPostProcessor recepcao() {
        return como("RECEPCAO", recepcao);
    }

    private RequestPostProcessor admin() {
        return como("ADMIN", admin);
    }

    private ResultActions pagar(UUID id, String valor, RequestPostProcessor quem) throws Exception {
        return mvc.perform(post("/api/cobrancas/" + id + "/pagar").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content("{\"valor\": " + valor + ", \"meioDePagamento\": \"PIX\"}"));
    }

    private ResultActions acao(UUID id, String acao, String motivo, RequestPostProcessor quem) throws Exception {
        return mvc.perform(post("/api/cobrancas/" + id + "/" + acao).with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(motivo == null ? "{}" : "{\"motivo\": \"" + motivo + "\"}"));
    }

    private String statusNoBanco(UUID id) {
        return jdbc.queryForObject("SELECT status FROM cobrancas WHERE id = ?", String.class, id);
    }

    private String trilha(UUID id, String acao) {
        return jdbc.queryForObject("SELECT autor || ' ' || alteracoes::text FROM auditoria WHERE entidade = 'COBRANCA' "
                + "AND entidade_id = ? AND acao = ?", String.class, id, acao);
    }

    @Test
    void listaDoAlunoComODevidoDeHoje() throws Exception {
        UUID atrasada = cobranca(1, 30);
        UUID emDia = cobranca(2, 0);

        mvc.perform(get("/api/cobrancas").param("alunoId", aluno.toString()).with(recepcao()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(atrasada.toString()))
                .andExpect(jsonPath("$[0].situacao").value("ATRASADA"))
                .andExpect(jsonPath("$[0].diasDeAtraso").value(30))
                .andExpect(jsonPath("$[0].devidoHoje.acrescimo").value(2.00))
                .andExpect(jsonPath("$[0].devidoHoje.juros").value(1.00))
                .andExpect(jsonPath("$[0].devidoHoje.total").value(103.00))
                .andExpect(jsonPath("$[1].id").value(emDia.toString()))
                .andExpect(jsonPath("$[1].situacao").value("PENDENTE"))
                .andExpect(jsonPath("$[1].devidoHoje.total").value(100.00))
                .andExpect(jsonPath("$[1].pagamento").value(nullValue()));
    }

    /** A configuração de hoje vale para todas, inclusive as que já estavam atrasadas (decisão do dono). */
    @Test
    void mudancaNaConfiguracaoValeParaTodas() throws Exception {
        UUID atrasada = cobranca(1, 30);
        jdbc.update("UPDATE configuracao_cobranca SET tipo_acrescimo = 'VALOR', acrescimo = 7, juros_ao_mes = 2");

        // R$ 7 cortado em 2% de 100 (CDC); juros de 2% × 30 dias.
        mvc.perform(get("/api/cobrancas/" + atrasada).with(recepcao()))
                .andExpect(jsonPath("$.devidoHoje.acrescimo").value(2.00))
                .andExpect(jsonPath("$.devidoHoje.juros").value(2.00))
                .andExpect(jsonPath("$.devidoHoje.total").value(104.00));
        pagar(atrasada, "103.00", recepcao()).andExpect(status().isConflict());
        pagar(atrasada, "104.00", recepcao()).andExpect(status().isOk());
    }

    @Test
    void recepcaoPagaOValorExatoEVaiParaATrilha() throws Exception {
        UUID id = cobranca(1, 30);

        pagar(id, "103.00", recepcao())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("PAGA"))
                .andExpect(jsonPath("$.devidoHoje").value(nullValue()))
                .andExpect(jsonPath("$.pagamento.total").value(103.00))
                .andExpect(jsonPath("$.pagamento.acrescimo").value(2.00))
                .andExpect(jsonPath("$.pagamento.juros").value(1.00))
                .andExpect(jsonPath("$.pagamento.meio").value("PIX"))
                .andExpect(jsonPath("$.pagamento.por").value(recepcao.toString()));

        assertThat(trilha(id, "PAGA")).startsWith(recepcao.toString())
                .contains("\"status\": {\"antes\": \"PENDENTE\", \"depois\": \"PAGA\"}")
                .contains("\"pagoTotal\": {\"antes\": null, \"depois\": 103.00}");
    }

    @Test
    void valorDiferenteDoDevido409ComODevido() throws Exception {
        UUID id = cobranca(1, 30);

        pagar(id, "100.00", recepcao())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Valor diferente do devido"))
                .andExpect(jsonPath("$.devidoHoje.total").value(103.00))
                .andExpect(jsonPath("$.devidoHoje.diasDeAtraso").value(30));
        assertThat(statusNoBanco(id)).isEqualTo("PENDENTE");
    }

    @Test
    void naoPagaDuasVezes() throws Exception {
        UUID id = cobranca(1, 0);
        pagar(id, "100", recepcao()).andExpect(status().isOk());

        pagar(id, "100", recepcao())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A cobrança já está paga"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"valor\": 100}", "{\"meioDePagamento\": \"PIX\"}",
            "{\"valor\": 100, \"meioDePagamento\": \"CHEQUE\"}"})
    void pagamentoIncompleto400(String corpo) throws Exception {
        UUID id = cobranca(1, 0);
        mvc.perform(post("/api/cobrancas/" + id + "/pagar").with(recepcao()).contentType(MediaType.APPLICATION_JSON)
                .content(corpo)).andExpect(status().isBadRequest());
        assertThat(statusNoBanco(id)).isEqualTo("PENDENTE");
    }

    /** Estorno: só o ADMIN, com motivo; a cobrança volta a dever (decisão do dono). */
    @Test
    void estornoVoltaADever() throws Exception {
        UUID id = cobranca(1, 0);
        pagar(id, "100", recepcao()).andExpect(status().isOk());

        acao(id, "estornar", "Pago em dobro", recepcao()).andExpect(status().isForbidden());
        acao(id, "estornar", null, admin()).andExpect(status().isBadRequest());
        acao(id, "estornar", "   ", admin()).andExpect(status().isBadRequest());
        assertThat(statusNoBanco(id)).isEqualTo("PAGA");

        acao(id, "estornar", "Pago em dobro", admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("PENDENTE"))
                .andExpect(jsonPath("$.pagamento").value(nullValue()))
                .andExpect(jsonPath("$.devidoHoje.total").value(100.00));
        assertThat(trilha(id, "ESTORNADA")).startsWith(admin.toString())
                .contains("\"motivo\": {\"antes\": null, \"depois\": \"Pago em dobro\"}");
        acao(id, "estornar", "De novo", admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("não há pagamento")));
    }

    private List<Map<String, Object>> lancamentosNoBanco(UUID id) {
        return jdbc.queryForList("SELECT id, tipo, estorna, total, meio_de_pagamento, registrado_por, motivo "
                + "FROM lancamentos WHERE cobranca_id = ? ORDER BY registrado_em, tipo DESC", id);
    }

    /**
     * O estorno não apaga o pagamento: ele continua no registro, com o estorno ao lado, e o
     * novo pagamento entra depois (ADR 0025). O caixa confere o que entrou e o que saiu.
     */
    @Test
    void registroGuardaPagamentoEstornoENovoPagamento() throws Exception {
        UUID id = cobranca(1, 15);
        pagar(id, "102.50", recepcao()).andExpect(status().isOk());
        relogio.adiantar(Duration.ofMinutes(5));
        acao(id, "estornar", "Cartão contestado", admin()).andExpect(status().isOk());
        relogio.adiantar(Duration.ofMinutes(5));
        mvc.perform(post("/api/cobrancas/" + id + "/pagar").with(como("RECEPCAO", outraRecepcao))
                .contentType(MediaType.APPLICATION_JSON).content("{\"valor\": 102.50, \"meioDePagamento\": \"DINHEIRO\"}"))
                .andExpect(status().isOk());

        List<Map<String, Object>> registro = lancamentosNoBanco(id);
        assertThat(registro).extracting(l -> l.get("tipo")).containsExactly("PAGAMENTO", "ESTORNO", "PAGAMENTO");
        assertThat(registro.get(0)).containsEntry("meio_de_pagamento", "PIX").containsEntry("registrado_por", recepcao);
        assertThat(registro.get(1)).containsEntry("estorna", registro.get(0).get("id"))
                .containsEntry("meio_de_pagamento", "PIX").containsEntry("registrado_por", admin)
                .containsEntry("motivo", "Cartão contestado");
        assertThat(registro.get(2)).containsEntry("meio_de_pagamento", "DINHEIRO")
                .containsEntry("registrado_por", outraRecepcao).containsEntry("estorna", null);
        assertThat(registro).extracting(l -> ((BigDecimal) l.get("total")).toPlainString())
                .containsOnly("102.50");

        mvc.perform(get("/api/cobrancas/" + id + "/lancamentos").with(recepcao()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tipo").value("PAGAMENTO"))
                .andExpect(jsonPath("$[0].acrescimo").value(2.00))
                .andExpect(jsonPath("$[0].juros").value(0.50))
                .andExpect(jsonPath("$[1].tipo").value("ESTORNO"))
                .andExpect(jsonPath("$[1].estorna").value(registro.get(0).get("id").toString()))
                .andExpect(jsonPath("$[1].motivo").value("Cartão contestado"))
                .andExpect(jsonPath("$[2].meio").value("DINHEIRO"));

        // O segundo estorno é do pagamento em vigor, não do que já foi estornado.
        relogio.adiantar(Duration.ofMinutes(5));
        acao(id, "estornar", "Nota falsa", admin()).andExpect(status().isOk());
        assertThat(lancamentosNoBanco(id)).hasSize(4).last()
                .satisfies(l -> assertThat(l).containsEntry("tipo", "ESTORNO")
                        .containsEntry("estorna", registro.get(2).get("id"))
                        .containsEntry("meio_de_pagamento", "DINHEIRO"));
    }

    /** Recusados (valor errado, motivo vazio, já paga) não deixam lançamento nenhum. */
    @Test
    void operacaoRecusadaNaoEntraNoRegistro() throws Exception {
        UUID id = cobranca(1, 0);
        pagar(id, "99", recepcao()).andExpect(status().isConflict());
        acao(id, "estornar", "Engano", admin()).andExpect(status().isConflict());
        assertThat(lancamentosNoBanco(id)).isEmpty();

        pagar(id, "100", recepcao()).andExpect(status().isOk());
        pagar(id, "100", recepcao()).andExpect(status().isConflict());
        acao(id, "estornar", "  ", admin()).andExpect(status().isBadRequest());
        assertThat(lancamentosNoBanco(id)).extracting(l -> l.get("tipo")).containsExactly("PAGAMENTO");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTRUTOR", "ALUNO"})
    void registroSoParaAEquipe(String perfil) throws Exception {
        UUID id = cobranca(1, 0);
        mvc.perform(get("/api/cobrancas/" + id + "/lancamentos").with(como(perfil, UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/cobrancas/" + id + "/lancamentos").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/cobrancas/" + UUID.randomUUID() + "/lancamentos").with(recepcao()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelamentoSoPeloAdminESoEmAberto() throws Exception {
        UUID paga = cobranca(1, 0);
        UUID emAberto = cobranca(2, 0);
        pagar(paga, "100", recepcao()).andExpect(status().isOk());

        acao(emAberto, "cancelar", "Bolsa", recepcao()).andExpect(status().isForbidden());
        acao(paga, "cancelar", "Bolsa", admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("estorne")));
        acao(emAberto, "cancelar", null, admin()).andExpect(status().isBadRequest());

        acao(emAberto, "cancelar", "Bolsa de estudo", admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("CANCELADA"))
                .andExpect(jsonPath("$.cancelamento.motivo").value("Bolsa de estudo"))
                .andExpect(jsonPath("$.cancelamento.por").value(admin.toString()))
                .andExpect(jsonPath("$.devidoHoje").value(nullValue()));
        assertThat(trilha(emAberto, "CANCELADA")).startsWith(admin.toString());
        pagar(emAberto, "100", recepcao())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A cobrança está cancelada"));
    }

    private RequestPostProcessor oAluno(UUID alunoId) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("alunoId", alunoId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ALUNO"));
    }

    /** O aluno vê as dele, sem os ids da equipe; o id vem do token. */
    @Test
    void alunoVeAsProprias() throws Exception {
        UUID paga = cobranca(1, 30);
        UUID emAberto = cobranca(2, 0);
        pagar(paga, "103.00", recepcao()).andExpect(status().isOk());

        mvc.perform(get("/api/cobrancas/me").with(oAluno(aluno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(paga.toString()))
                .andExpect(jsonPath("$[0].situacao").value("PAGA"))
                .andExpect(jsonPath("$[0].pagoTotal").value(103.00))
                .andExpect(jsonPath("$[0].meioDePagamento").value("PIX"))
                .andExpect(jsonPath("$[0].pagaEm").exists())
                .andExpect(jsonPath("$[0].pagamento").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(emAberto.toString()))
                .andExpect(jsonPath("$[1].devidoHoje.total").value(100.00));
        String corpo = mvc.perform(get("/api/cobrancas/me").with(oAluno(aluno))).andReturn().getResponse()
                .getContentAsString();
        assertThat(corpo).doesNotContain(recepcao.toString());
    }

    @Test
    void alunoSemCadastroNoTokenNaoVe() throws Exception {
        mvc.perform(get("/api/cobrancas/me").with(como("ALUNO", UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/cobrancas/me").with(recepcao())).andExpect(status().isForbidden());
        // Só o perfil ALUNO, mesmo que um token de outro perfil traga um alunoId.
        mvc.perform(get("/api/cobrancas/me").with(jwt().jwt(j -> j.subject(recepcao.toString())
                        .claim("alunoId", aluno.toString())).authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/cobrancas/me").with(oAluno(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    /** A lista da recepção: só as em aberto já vencidas, da mais antiga para a mais nova. */
    @Test
    void listaDeAtrasadas() throws Exception {
        UUID maisAntiga = cobranca(1, 40);
        UUID atrasada = cobranca(2, 5);
        cobranca(3, 0);
        UUID paga = cobranca(4, 20);
        pagar(paga, "102.67", recepcao()).andExpect(status().isOk());

        String corpo = mvc.perform(get("/api/cobrancas/atrasadas").param("tamanho", "100").with(recepcao()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(corpo, "$.conteudo[*].id");
        assertThat(ids).containsSubsequence(maisAntiga.toString(), atrasada.toString())
                .doesNotContain(paga.toString());
        assertThat(ids.stream().filter(id -> id.equals(maisAntiga.toString()) || id.equals(atrasada.toString())))
                .hasSize(2);
        List<String> situacoes = JsonPath.read(corpo, "$.conteudo[*].situacao");
        assertThat(situacoes).containsOnly("ATRASADA");
        assertThat((Integer) JsonPath.read(corpo, "$.totalElementos")).isEqualTo(ids.size());
    }

    @Test
    void listaDeAtrasadasPaginada() throws Exception {
        for (int i = 0; i < 3; i++) {
            cobranca(1 + i, 10 + i);
        }
        String corpo = mvc.perform(get("/api/cobrancas/atrasadas").param("tamanho", "2").param("pagina", "0")
                .with(recepcao())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int total = JsonPath.read(corpo, "$.totalElementos");
        assertThat(total).isGreaterThanOrEqualTo(3);
        assertThat((List<?>) JsonPath.read(corpo, "$.conteudo")).hasSize(2);
        assertThat((Integer) JsonPath.read(corpo, "$.totalPaginas")).isEqualTo((total + 1) / 2);
        String ultima = mvc.perform(get("/api/cobrancas/atrasadas").param("tamanho", "2")
                .param("pagina", String.valueOf((total - 1) / 2)).with(recepcao()))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(ultima, "$.conteudo")).hasSize(total % 2 == 0 ? 2 : 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tamanho=101", "tamanho=0", "pagina=-1"})
    void paginacaoForaDosLimites400(String parametro) throws Exception {
        String[] partes = parametro.split("=");
        mvc.perform(get("/api/cobrancas/atrasadas").param(partes[0], partes[1]).with(recepcao()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTRUTOR", "ALUNO"})
    void listaDeAtrasadasSoParaAEquipe(String perfil) throws Exception {
        mvc.perform(get("/api/cobrancas/atrasadas").with(como(perfil, UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void inexistente404() throws Exception {
        UUID nenhuma = UUID.randomUUID();
        mvc.perform(get("/api/cobrancas/" + nenhuma).with(recepcao())).andExpect(status().isNotFound());
        pagar(nenhuma, "100", recepcao()).andExpect(status().isNotFound());
        acao(nenhuma, "cancelar", "x", admin()).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTRUTOR", "ALUNO"})
    void instrutorEAlunoNaoMexem(String perfil) throws Exception {
        UUID id = cobranca(1, 0);
        RequestPostProcessor quem = como(perfil, UUID.randomUUID());
        mvc.perform(get("/api/cobrancas").param("alunoId", aluno.toString()).with(quem)).andExpect(status().isForbidden());
        mvc.perform(get("/api/cobrancas/" + id).with(quem)).andExpect(status().isForbidden());
        pagar(id, "100", quem).andExpect(status().isForbidden());
    }

    @Test
    void semTokenNaoVe() throws Exception {
        mvc.perform(get("/api/cobrancas").param("alunoId", aluno.toString())).andExpect(status().isUnauthorized());
    }

    /**
     * Duas recepcionistas registram o mesmo pagamento ao mesmo tempo: a cobrança travada faz
     * a segunda ver que já está paga. Cada uma ganha em parte das rodadas.
     */
    @Test
    void pagamentosSimultaneosSoUmPassa() throws Exception {
        Set<UUID> quemPagou = new HashSet<>();
        for (int rodada = 0; rodada < 20; rodada++) {
            UUID id = cobranca(10 + rodada, 0);
            List<Integer> resultados = correr(() -> pagar(id, "100", recepcao()), () -> pagar(id, "100", como("RECEPCAO", outraRecepcao)));
            assertThat(resultados).as("rodada %d", rodada).containsExactly(200, 409);
            quemPagou.add(jdbc.queryForObject("SELECT paga_por FROM cobrancas WHERE id = ?", UUID.class, id));
        }
        assertThat(quemPagou).containsExactlyInAnyOrder(recepcao, outraRecepcao);
    }

    /** Pagar e cancelar ao mesmo tempo: só uma operação vale, e cada uma ganha em parte das rodadas. */
    @Test
    void pagarECancelarAoMesmoTempo() throws Exception {
        Set<String> finais = new HashSet<>();
        for (int rodada = 0; rodada < 20; rodada++) {
            UUID id = cobranca(10 + rodada, 0);
            List<Integer> resultados = correr(() -> pagar(id, "100", recepcao()), () -> acao(id, "cancelar", "Bolsa", admin()));
            assertThat(resultados).as("rodada %d", rodada).containsExactly(200, 409);
            finais.add(statusNoBanco(id));
        }
        assertThat(finais).containsExactlyInAnyOrder("PAGA", "CANCELADA");
    }

    private interface Chamada {
        ResultActions fazer() throws Exception;
    }

    private static List<Integer> correr(Chamada a, Chamada b) {
        CountDownLatch largada = new CountDownLatch(1);
        List<CompletableFuture<Integer>> lados = new ArrayList<>();
        for (Chamada chamada : List.of(a, b)) {
            lados.add(CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    // Um atraso aleatório pequeno: cada lado chega primeiro em parte das rodadas.
                    Thread.sleep(ThreadLocalRandom.current().nextInt(3));
                    var resposta = chamada.fazer().andReturn().getResponse();
                    // Quem perde precisa ter visto a cobrança já alterada (trava), e não
                    // esbarrado na versão: as duas dão 409, só o título diz qual barreira agiu.
                    return resposta.getStatus() == 409
                            && !resposta.getContentAsString().contains("Operação não permitida") ? -409
                            : resposta.getStatus();
                } catch (Exception e) {
                    return -1;
                }
            }));
        }
        largada.countDown();
        return lados.stream().map(CompletableFuture::join).sorted().toList();
    }
}
