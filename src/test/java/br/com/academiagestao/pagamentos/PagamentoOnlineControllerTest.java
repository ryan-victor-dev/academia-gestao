package br.com.academiagestao.pagamentos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O pagamento online de ponta a ponta com a empresa simulada (ADR 0034). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"academia.pagamentos.provedor=simulado",
        "academia.pagamentos.token-do-aviso-simulado=token-de-teste-do-aviso",
        "academia.pagamentos.intervalo-da-consulta=1h"})
@AutoConfigureMockMvc
class PagamentoOnlineControllerTest {

    private static final String TOKEN = "token-de-teste-do-aviso";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    ProvedorSimulado simulado;

    @Autowired
    PagamentoOnlineService service;

    private final UUID aluno = UUID.randomUUID();
    private final UUID outroAluno = UUID.randomUUID();
    private final UUID usuarioDoAluno = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID matricula = UUID.randomUUID();
    private final UUID matriculaDoOutro = UUID.randomUUID();
    private LocalDate hoje;

    @BeforeEach
    void preparar() {
        hoje = Calendario.hoje(Instant.now()).plusDays(1);
        irPara(hoje.atTime(12, 0));
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email) VALUES (?, 'Aluna Online', ?, ?)", aluno,
                CpfsParaTeste.aleatorio(), aluno + "@teste.local");
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Outro', ?)", outroAluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Admin', ?, 'x', 'ADMIN')",
                admin, admin + "@teste.local");
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, aluno_id) VALUES (?, 'Aluna', ?, 'x', "
                + "'ALUNO', ?)", usuarioDoAluno, usuarioDoAluno + "@teste.local", aluno);
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 3)", plano, "P " + plano);
        for (UUID[] m : List.of(new UUID[] {matricula, aluno}, new UUID[] {matriculaDoOutro, outroAluno})) {
            LocalDate inicio = hoje.minusDays(30);
            jdbc.update("""
                    INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim,
                                            criada_em)
                    VALUES (?, ?, ?, 'P', 100, 3, ?, ?, now())""", m[0], m[1], plano, inicio,
                    inicio.plusMonths(3).minusDays(1));
        }
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        jdbc.update("DELETE FROM auditoria WHERE entidade_id IN (SELECT id FROM pagamentos_online WHERE aluno_id = ?) "
                + "OR entidade_id IN (SELECT id FROM cobrancas WHERE aluno_id IN (?, ?))", aluno, aluno, outroAluno);
        jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", ConfiguracaoDoPagamentoOnlineController.ID_NA_TRILHA);
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("alunos", outroAluno);
        limpeza.apagar("planos", plano);
        limpeza.apagar("usuarios", usuarioDoAluno);
        limpeza.apagar("usuarios", admin);
        jdbc.update("UPDATE configuracao_pagamento_online SET pix_ligado = true, boleto_ligado = true, "
                + "adiantar = 'PROXIMA', dias_do_boleto = 3");
    }

    private void irPara(java.time.LocalDateTime quando) {
        relogio.fixar(quando.atZone(Calendario.FUSO).toInstant());
    }

    private static Instant inicioDo(LocalDate dia) {
        return dia.atStartOfDay(Calendario.FUSO).toInstant();
    }

    /** Uma cobrança de 100,00 vencendo {@code diasAtras} dias antes de hoje (negativo: no futuro). */
    private UUID cobranca(UUID matriculaId, UUID alunoId, int parcela, int diasAtras) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, ?, 'MENSALIDADE', ?, 100, ?, 'PENDENTE')""", id, matriculaId, alunoId, parcela,
                hoje.minusDays(diasAtras));
        return id;
    }

    private UUID cobranca(int parcela, int diasAtras) {
        return cobranca(matricula, aluno, parcela, diasAtras);
    }

    private RequestPostProcessor doAluno() {
        return jwt().jwt(j -> j.subject(usuarioDoAluno.toString()).claim("alunoId", aluno.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ALUNO"));
    }

    private RequestPostProcessor doAdmin() {
        return jwt().jwt(j -> j.subject(admin.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private ResultActions gerar(UUID cobrancaId, String forma) throws Exception {
        return mvc.perform(post("/api/pagamentos-online/me").with(doAluno()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cobrancaId\": \"" + cobrancaId + "\", \"forma\": \"" + forma + "\"}"));
    }

    private Map<String, Object> gerado(UUID cobrancaId, String forma) throws Exception {
        String corpo = gerar(cobrancaId, forma).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(corpo, "$");
    }

    private ResultActions simular(Object id) throws Exception {
        return mvc.perform(post("/api/pagamentos-online/me/" + id + "/simular-pagamento").with(doAluno()));
    }

    private ResultActions aviso(String token, String corpo) throws Exception {
        var requisicao = post("/api/pagamentos-online/aviso").content(corpo);
        if (token != null) {
            requisicao.header("X-Aviso-Token", token);
        }
        return mvc.perform(requisicao);
    }

    private String situacao(Object id) {
        return jdbc.queryForObject("SELECT situacao FROM pagamentos_online WHERE id = ?::uuid", String.class,
                id.toString());
    }

    private String idExterno(Object id) {
        return jdbc.queryForObject("SELECT id_externo FROM pagamentos_online WHERE id = ?::uuid", String.class,
                id.toString());
    }

    @Test
    void mostraAsPagaveisComOValorDeHoje() throws Exception {
        UUID atrasada = cobranca(1, 30);
        UUID proxima = cobranca(2, -5);
        cobranca(3, -35);
        cobranca(matriculaDoOutro, outroAluno, 1, 30);

        mvc.perform(get("/api/pagamentos-online/me").with(doAluno()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.ligado").value(true))
                .andExpect(jsonPath("$.simulado").value(true))
                .andExpect(jsonPath("$.pix").value(true))
                .andExpect(jsonPath("$.boleto").value(true))
                .andExpect(jsonPath("$.pagaveis[*].cobrancaId", contains(atrasada.toString(), proxima.toString())))
                .andExpect(jsonPath("$.pagaveis[0].total").value(103.00))
                .andExpect(jsonPath("$.pagaveis[0].diasDeAtraso").value(30))
                .andExpect(jsonPath("$.pagaveis[0].pix").value(nullValue()))
                .andExpect(jsonPath("$.pagaveis[1].total").value(100.00));
    }

    /** O Pix vale até o fim do dia; pedir de novo devolve o mesmo, sem gerar outro na empresa. */
    @Test
    void geraPixEDevolveOMesmoNoMesmoDia() throws Exception {
        UUID atrasada = cobranca(1, 30);

        Map<String, Object> pix = gerado(atrasada, "PIX");

        assertThat(pix.get("total")).isEqualTo(103.0);
        assertThat(pix.get("acrescimo")).isEqualTo(2.0);
        assertThat(pix.get("juros")).isEqualTo(1.0);
        assertThat((String) pix.get("pixCopiaECola")).startsWith("PIX-SIMULADO");
        assertThat(pix.get("situacao")).isEqualTo("AGUARDANDO");
        assertThat(Instant.parse((String) pix.get("validoAte"))).isEqualTo(inicioDo(hoje.plusDays(1)));
        gerar(atrasada, "PIX").andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").value(pix.get("id")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamentos_online WHERE cobranca_id = ?", Integer.class,
                atrasada)).isEqualTo(1);
        mvc.perform(get("/api/pagamentos-online/me").with(doAluno()))
                .andExpect(jsonPath("$.pagaveis[0].pix.id").value(pix.get("id")))
                .andExpect(jsonPath("$.pagaveis[0].boleto").value(nullValue()));
    }

    /** No dia seguinte, o Pix de ontem não vale: sai na empresa e aqui, e um novo vem com o valor de hoje. */
    @Test
    void pixDeOntemETrocado() throws Exception {
        UUID atrasada = cobranca(1, 30);
        Map<String, Object> ontem = gerado(atrasada, "PIX");

        irPara(hoje.plusDays(1).atTime(9, 0));
        Map<String, Object> novo = gerado(atrasada, "PIX");

        assertThat(novo.get("id")).isNotEqualTo(ontem.get("id"));
        assertThat(situacao(ontem.get("id"))).isEqualTo("CANCELADO");
        assertThat(simulado.consultar(idExterno(ontem.get("id"))).estado()).isEqualTo(ProvedorDePagamento.Estado.ENCERRADO);
        assertThat(new BigDecimal(novo.get("juros").toString())).isGreaterThan(new BigDecimal("1.00"));
    }

    /** O boleto de uma vencida vale os dias configurados (3), com valor fixo; o de uma a vencer, até o vencimento. */
    @Test
    void validadeDoBoleto() throws Exception {
        UUID atrasada = cobranca(1, 30);
        UUID proxima = cobranca(2, -5);

        Map<String, Object> vencido = gerado(atrasada, "BOLETO");
        Map<String, Object> aVencer = gerado(proxima, "BOLETO");

        assertThat(Instant.parse((String) vencido.get("validoAte"))).isEqualTo(inicioDo(hoje.plusDays(4)));
        assertThat((String) vencido.get("boletoLinhaDigitavel")).hasSize(47);
        assertThat(Instant.parse((String) aVencer.get("validoAte"))).isEqualTo(inicioDo(hoje.plusDays(6)));
        // Dois dias depois, o boleto vencido ainda vale com o valor de antes (os juros não andam nele).
        irPara(hoje.plusDays(2).atTime(9, 0));
        gerar(atrasada, "BOLETO").andExpect(jsonPath("$.id").value(vencido.get("id")))
                .andExpect(jsonPath("$.total").value(103.00));
    }

    @Test
    void recusas() throws Exception {
        cobranca(1, 30);
        cobranca(2, -5);
        UUID depois = cobranca(3, -35);
        UUID doOutro = cobranca(matriculaDoOutro, outroAluno, 1, 30);

        gerar(depois, "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("ainda não pode ser paga")));
        gerar(doOutro, "PIX").andExpect(status().isNotFound());
        gerar(UUID.randomUUID(), "PIX").andExpect(status().isNotFound());
        jdbc.update("UPDATE configuracao_pagamento_online SET pix_ligado = false");
        gerar(cobranca(4, 0), "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Pix não está disponível")));
        mvc.perform(post("/api/pagamentos-online/me").with(doAluno()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"forma\": \"PIX\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/pagamentos-online/me").with(doAdmin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagamentos-online/a-devolver").with(doAluno())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamentos_online WHERE aluno_id IN (?, ?)",
                Integer.class, aluno, outroAluno)).isZero();
    }

    /** Pago pelo app: a cobrança fica paga sem caixa nenhum aberto, por quem gerou. */
    @Test
    void pagoPeloApp() throws Exception {
        UUID atrasada = cobranca(1, 30);
        Map<String, Object> pix = gerado(atrasada, "PIX");

        simular(pix.get("id")).andExpect(status().isNoContent());

        assertThat(situacao(pix.get("id"))).isEqualTo("PAGO");
        Map<String, Object> cobranca = jdbc.queryForMap("SELECT * FROM cobrancas WHERE id = ?", atrasada);
        assertThat(cobranca.get("status")).isEqualTo("PAGA");
        assertThat(cobranca.get("meio_de_pagamento")).isEqualTo("PIX_ONLINE");
        assertThat((BigDecimal) cobranca.get("pago_total")).isEqualByComparingTo("103.00");
        assertThat(cobranca.get("paga_por")).isEqualTo(usuarioDoAluno);
        Map<String, Object> lancamento = jdbc.queryForMap("SELECT * FROM lancamentos WHERE cobranca_id = ?", atrasada);
        assertThat(lancamento.get("caixa_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade = 'PAGAMENTO_ONLINE' "
                + "AND entidade_id = ?::uuid AND acao = 'PAGO'", Integer.class, pix.get("id").toString())).isEqualTo(1);
        mvc.perform(get("/api/pagamentos-online/me").with(doAluno()))
                .andExpect(jsonPath("$.pagaveis[*].cobrancaId", not(hasItem(atrasada.toString()))));
        // Pago, não simula de novo nem paga duas vezes.
        simular(pix.get("id")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lancamentos WHERE cobranca_id = ?", Integer.class,
                atrasada)).isEqualTo(1);
    }

    @Test
    void naoSimulaOPagamentoDeOutroAluno() throws Exception {
        UUID atrasada = cobranca(1, 30);
        Map<String, Object> pix = gerado(atrasada, "PIX");
        RequestPostProcessor outro = jwt().jwt(j -> j.subject(usuarioDoAluno.toString())
                .claim("alunoId", outroAluno.toString())).authorities(new SimpleGrantedAuthority("ROLE_ALUNO"));

        mvc.perform(post("/api/pagamentos-online/me/" + pix.get("id") + "/simular-pagamento").with(outro))
                .andExpect(status().isNotFound());
        assertThat(situacao(pix.get("id"))).isEqualTo("AGUARDANDO");
    }

    /**
     * Pagou o Pix e o boleto da mesma cobrança: o segundo dinheiro não paga nada e fica a
     * devolver. O ADMIN vê, com o nome do aluno, e marca como devolvido.
     */
    @Test
    void dinheiroADevolver() throws Exception {
        UUID atrasada = cobranca(1, 30);
        Map<String, Object> pix = gerado(atrasada, "PIX");
        Map<String, Object> boleto = gerado(atrasada, "BOLETO");
        simular(pix.get("id")).andExpect(status().isNoContent());

        simular(boleto.get("id")).andExpect(status().isNoContent());

        assertThat(situacao(boleto.get("id"))).isEqualTo("A_DEVOLVER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lancamentos WHERE cobranca_id = ?", Integer.class,
                atrasada)).isEqualTo(1);
        mvc.perform(get("/api/pagamentos-online/a-devolver").with(doAdmin()))
                .andExpect(jsonPath("$[?(@.id == '" + boleto.get("id") + "')].alunoNome", contains("Aluna Online")))
                .andExpect(jsonPath("$[?(@.id == '" + boleto.get("id") + "')].recebido", contains(103.00)))
                .andExpect(jsonPath("$[?(@.id == '" + boleto.get("id") + "')].motivoDaDevolucao",
                        contains(containsString("já não estava em aberto"))));

        mvc.perform(post("/api/pagamentos-online/" + boleto.get("id") + "/devolvido").with(doAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("DEVOLVIDO"))
                .andExpect(jsonPath("$.devolvidoPor").value(admin.toString()));
        mvc.perform(post("/api/pagamentos-online/" + boleto.get("id") + "/devolvido").with(doAdmin()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/pagamentos-online/" + pix.get("id") + "/devolvido").with(doAdmin()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/pagamentos-online/" + UUID.randomUUID() + "/devolvido").with(doAdmin()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade = 'PAGAMENTO_ONLINE' "
                + "AND entidade_id = ?::uuid AND acao = 'DEVOLVIDO' AND autor = ?", Integer.class,
                boleto.get("id").toString(), admin.toString())).isEqualTo(1);
    }

    /** O aviso da empresa só dispara a consulta: sem o token, nada; com ele, vale o que a empresa diz. */
    @Test
    void avisoDaEmpresa() throws Exception {
        UUID atrasada = cobranca(1, 30);
        Map<String, Object> pix = gerado(atrasada, "PIX");
        String externo = idExterno(pix.get("id"));

        // Ainda não pago na empresa: o aviso (verdadeiro ou não) não muda nada.
        aviso(TOKEN, externo).andExpect(status().isNoContent());
        assertThat(situacao(pix.get("id"))).isEqualTo("AGUARDANDO");

        simulado.simularPagamento(externo, null);
        aviso(null, externo).andExpect(status().isUnauthorized());
        aviso("token-errado", externo).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Aviso recusado"));
        assertThat(situacao(pix.get("id"))).isEqualTo("AGUARDANDO");
        aviso(TOKEN, "sim_desconhecido").andExpect(status().isNoContent());

        aviso(TOKEN, externo).andExpect(status().isNoContent());
        assertThat(situacao(pix.get("id"))).isEqualTo("PAGO");
    }

    @Test
    void valorDiferenteFicaADevolver() throws Exception {
        UUID atrasada = cobranca(1, 30);
        Map<String, Object> pix = gerado(atrasada, "PIX");
        String externo = idExterno(pix.get("id"));

        simulado.simularPagamento(externo, new BigDecimal("50.00"));
        aviso(TOKEN, externo).andExpect(status().isNoContent());

        assertThat(situacao(pix.get("id"))).isEqualTo("A_DEVOLVER");
        assertThat(jdbc.queryForObject("SELECT motivo_da_devolucao FROM pagamentos_online WHERE id = ?::uuid",
                String.class, pix.get("id").toString())).contains("50.00").contains("103.00");
        assertThat(jdbc.queryForObject("SELECT status FROM cobrancas WHERE id = ?", String.class, atrasada))
                .isEqualTo("PENDENTE");
    }

    /** A consulta periódica acha o pago sem aviso, e expira o que passou do prazo e da tolerância (1 h no Pix). */
    @Test
    void consultaPeriodica() throws Exception {
        Map<String, Object> pago = gerado(cobranca(1, 30), "PIX");
        Map<String, Object> esquecido = gerado(cobranca(2, -5), "PIX");
        simulado.simularPagamento(idExterno(pago.get("id")), null);

        irPara(hoje.plusDays(1).atTime(0, 30));
        service.consultarAguardando();
        assertThat(situacao(pago.get("id"))).isEqualTo("PAGO");
        assertThat(situacao(esquecido.get("id"))).isEqualTo("AGUARDANDO");

        irPara(hoje.plusDays(1).atTime(1, 1));
        service.consultarAguardando();
        assertThat(situacao(esquecido.get("id"))).isEqualTo("EXPIRADO");
        assertThat(simulado.consultar(idExterno(esquecido.get("id"))).estado())
                .isEqualTo(ProvedorDePagamento.Estado.ENCERRADO);
    }

    /** Pago depois de expirado (no último segundo): ainda paga a cobrança. */
    @Test
    void pagoDepoisDeExpiradoAindaPaga() throws Exception {
        UUID atrasada = cobranca(1, 30);
        Map<String, Object> pix = gerado(atrasada, "PIX");
        jdbc.update("UPDATE pagamentos_online SET situacao = 'EXPIRADO' WHERE id = ?::uuid", pix.get("id").toString());

        simulado.simularPagamento(idExterno(pix.get("id")), null);
        aviso(TOKEN, idExterno(pix.get("id"))).andExpect(status().isNoContent());

        assertThat(situacao(pix.get("id"))).isEqualTo("PAGO");
        assertThat(jdbc.queryForObject("SELECT status FROM cobrancas WHERE id = ?", String.class, atrasada))
                .isEqualTo("PAGA");
    }

    /**
     * O aviso e a consulta periódica ao mesmo tempo: a trava da linha faz um esperar o outro, e
     * o segundo vê que já foi registrado (sem ela, o segundo marcaria o dinheiro como a devolver).
     * Várias rodadas: uma só passaria por sorte.
     */
    @Test
    void avisoEConsultaAoMesmoTempo() throws Exception {
        for (int rodada = 0; rodada < 15; rodada++) {
            UUID id = cobranca(10 + rodada, 0);
            Map<String, Object> pix = gerado(id, "PIX");
            String externo = idExterno(pix.get("id"));
            simulado.simularPagamento(externo, null);
            CountDownLatch largada = new CountDownLatch(1);
            List<CompletableFuture<Void>> lados = List.of(1, 2).stream().map(i -> CompletableFuture.runAsync(() -> {
                try {
                    largada.await();
                    service.confirmar(externo);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            })).toList();
            largada.countDown();
            lados.forEach(CompletableFuture::join);

            assertThat(situacao(pix.get("id"))).as("rodada %d", rodada).isEqualTo("PAGO");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lancamentos WHERE cobranca_id = ?", Integer.class, id))
                    .as("rodada %d", rodada).isEqualTo(1);
        }
    }

    @Test
    void configuracaoDoAdmin() throws Exception {
        String etag = mvc.perform(get("/api/pagamentos-online/configuracao").with(doAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provedor").value("simulado"))
                .andExpect(jsonPath("$.simulado").value(true))
                .andExpect(jsonPath("$.adiantar").value("PROXIMA"))
                .andExpect(jsonPath("$.diasDoBoleto").value(3))
                .andReturn().getResponse().getHeader("ETag");
        String corpo = "{\"pixLigado\": true, \"boletoLigado\": false, \"adiantar\": \"TODAS\", \"diasDoBoleto\": 5}";

        mvc.perform(put("/api/pagamentos-online/configuracao").with(doAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(corpo)).andExpect(status().isPreconditionRequired());
        mvc.perform(put("/api/pagamentos-online/configuracao").with(doAdmin()).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo.replace("\"diasDoBoleto\": 5", "\"diasDoBoleto\": 31")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/pagamentos-online/configuracao").with(doAdmin()).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boletoLigado").value(false))
                .andExpect(jsonPath("$.adiantar").value("TODAS"))
                .andExpect(header().string("ETag", not(etag)));
        mvc.perform(put("/api/pagamentos-online/configuracao").with(doAdmin()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isPreconditionFailed());
        mvc.perform(get("/api/pagamentos-online/configuracao").with(doAluno())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade = 'CONFIGURACAO' "
                + "AND entidade_id = ?", Integer.class, ConfiguracaoDoPagamentoOnlineController.ID_NA_TRILHA))
                .isEqualTo(1);

        // Com TODAS, a terceira (que vence daqui a 35 dias) também se paga.
        cobranca(1, -5);
        UUID depois = cobranca(2, -35);
        gerar(depois, "PIX").andExpect(status().isCreated());
        gerar(depois, "BOLETO").andExpect(status().isConflict());
    }
}
