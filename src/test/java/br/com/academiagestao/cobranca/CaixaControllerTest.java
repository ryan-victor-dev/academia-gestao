package br.com.academiagestao.cobranca;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O caixa do dia pela API (ADR 0031): abrir, fechar, reabrir, e pagamento e estorno só com o
 * caixa aberto. O caixa é um por dia para a academia: cada teste usa dias seus, longe no
 * futuro, e apaga os caixas deles no fim.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CaixaControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    PlatformTransactionManager transacoes;

    private final UUID aluno = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID matricula = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID recepcao = UUID.randomUUID();
    /** O primeiro dia do teste: entre 3 e 30 anos no futuro, para não cruzar com outros testes. */
    private LocalDate dia;

    @BeforeEach
    void preparar() {
        dia = Calendario.hoje(java.time.Instant.now()).plusDays(1000 + ThreadLocalRandom.current().nextInt(10_000));
        irPara(dia);
        for (UUID usuario : List.of(admin, recepcao)) {
            jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'U', ?, 'x', ?)",
                    usuario, usuario + "@teste.local", usuario.equals(admin) ? "ADMIN" : "RECEPCAO");
        }
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 3)", plano, "P " + plano);
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'P', 100, 3, ?, ?, now())""", matricula, aluno, plano, dia, dia.plusMonths(3).minusDays(1));
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'COBRANCA' AND entidade_id IN "
                + "(SELECT id FROM cobrancas WHERE aluno_id = ?)", aluno);
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'CAIXA' AND entidade_id IN "
                + "(SELECT id FROM caixas WHERE aberto_por IN (?, ?))", admin, recepcao);
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("planos", plano);
        limpeza.apagarOnde("caixas", "aberto_por IN (?, ?)", admin, recepcao);
        for (UUID usuario : List.of(admin, recepcao)) {
            limpeza.apagar("usuarios", usuario);
        }
    }

    private void irPara(LocalDate data) {
        relogio.fixar(data.atTime(12, 0).atZone(Calendario.FUSO).toInstant());
    }

    /** Uma cobrança de 100,00 vencendo no dia de hoje (do relógio): devido = 100. */
    private UUID cobranca() {
        UUID id = UUID.randomUUID();
        LocalDate hoje = Calendario.hoje(relogio.instant());
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, ?, 'MENSALIDADE', ?, 100, ?, 'PENDENTE')""", id, matricula, aluno,
                ThreadLocalRandom.current().nextInt(1, 1_000_000), hoje);
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

    private ResultActions abrir(String troco, RequestPostProcessor quem) throws Exception {
        return mvc.perform(post("/api/caixas").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(troco == null ? "{}" : "{\"troco\": " + troco + "}"));
    }

    private ResultActions fechar(LocalDate data, String contado, String justificativa, RequestPostProcessor quem)
            throws Exception {
        String corpo = "{\"contado\": " + contado
                + (justificativa == null ? "" : ", \"justificativa\": \"" + justificativa + "\"") + "}";
        return mvc.perform(post("/api/caixas/" + data + "/fechar").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private ResultActions reabrir(LocalDate data, String motivo, RequestPostProcessor quem) throws Exception {
        return mvc.perform(post("/api/caixas/" + data + "/reabrir").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content(motivo == null ? "{}" : "{\"motivo\": \"" + motivo + "\"}"));
    }

    private ResultActions pagar(UUID cobranca, String meio) throws Exception {
        return mvc.perform(post("/api/cobrancas/" + cobranca + "/pagar").with(recepcao())
                .contentType(MediaType.APPLICATION_JSON).content("{\"valor\": 100, \"meioDePagamento\": \"" + meio + "\"}"));
    }

    private ResultActions estornar(UUID cobranca) throws Exception {
        return mvc.perform(post("/api/cobrancas/" + cobranca + "/estornar").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"Devolvido\"}"));
    }

    private UUID caixaDoDia(LocalDate data) {
        return jdbc.queryForObject("SELECT id FROM caixas WHERE dia = ?", UUID.class, data);
    }

    private String trilha(LocalDate data, String acao) {
        return jdbc.queryForObject("SELECT autor || ' ' || alteracoes::text FROM auditoria WHERE entidade = 'CAIXA' "
                + "AND entidade_id = ? AND acao = ?", String.class, caixaDoDia(data), acao);
    }

    private String statusNoBanco(UUID cobranca) {
        return jdbc.queryForObject("SELECT status FROM cobrancas WHERE id = ?", String.class, cobranca);
    }

    private int lancamentos(UUID cobranca) {
        return jdbc.queryForObject("SELECT count(*) FROM lancamentos WHERE cobranca_id = ?", Integer.class, cobranca);
    }

    @Test
    void abreRecebeEFechaSemDiferenca() throws Exception {
        abrir("50.00", recepcao())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dia").value(dia.toString()))
                .andExpect(jsonPath("$.troco").value(50.00))
                .andExpect(jsonPath("$.situacao").value("ABERTO"))
                .andExpect(jsonPath("$.abertoPor").value(recepcao.toString()))
                .andExpect(jsonPath("$.fechamentos", hasSize(0)));
        assertThat(trilha(dia, "ABERTO")).startsWith(recepcao.toString()).contains(dia.toString()).contains("50.00");

        // Dinheiro entra na conta; PIX e cartão não (o ADMIN os confere pelo relatório).
        UUID emDinheiro = cobranca();
        UUID outraEmDinheiro = cobranca();
        UUID noPix = cobranca();
        pagar(emDinheiro, "DINHEIRO").andExpect(status().isOk());
        pagar(outraEmDinheiro, "DINHEIRO").andExpect(status().isOk());
        pagar(noPix, "PIX").andExpect(status().isOk());
        // O estorno em dinheiro sai da gaveta.
        estornar(outraEmDinheiro).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lancamentos WHERE caixa_id = ?", Integer.class,
                caixaDoDia(dia))).isEqualTo(4);

        fechar(dia, "150", null, recepcao())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("FECHADO"))
                .andExpect(jsonPath("$.fechamentos", hasSize(1)))
                .andExpect(jsonPath("$.fechamentos[0].esperado").value(150.00))
                .andExpect(jsonPath("$.fechamentos[0].contado").value(150.00))
                .andExpect(jsonPath("$.fechamentos[0].diferenca").value(0.00))
                .andExpect(jsonPath("$.fechamentos[0].justificativa").value(nullValue()))
                .andExpect(jsonPath("$.fechamentos[0].por").value(recepcao.toString()));
        assertThat(trilha(dia, "FECHADO")).startsWith(recepcao.toString())
                .contains("\"situacao\": {\"antes\": \"ABERTO\", \"depois\": \"FECHADO\"}")
                .contains("\"esperado\": {\"antes\": null, \"depois\": 150.00}")
                .contains("\"diferenca\": {\"antes\": null, \"depois\": 0.00}");
        mvc.perform(get("/api/caixas/" + dia).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("FECHADO"))
                .andExpect(jsonPath("$.fechamentos[0].esperado").value(150.00));
    }

    @Test
    void semCaixaAbertoNadaEntraNemSai() throws Exception {
        UUID id = cobranca();

        pagar(id, "PIX").andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Caixa não permite a operação"))
                .andExpect(jsonPath("$.detail").value(containsString("não foi aberto")));
        assertThat(statusNoBanco(id)).isEqualTo("PENDENTE");

        // Uma paga (de outro jeito) também não se estorna sem caixa.
        abrir("0", recepcao()).andExpect(status().isCreated());
        pagar(id, "PIX").andExpect(status().isOk());
        irPara(dia.plusDays(1));
        estornar(id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("não foi aberto")));
        assertThat(statusNoBanco(id)).isEqualTo("PAGA");
        assertThat(lancamentos(id)).isEqualTo(1);
    }

    @Test
    void diaFechadoNaoRecebeAteOAdminReabrir() throws Exception {
        abrir("0", recepcao()).andExpect(status().isCreated());
        UUID paga = cobranca();
        pagar(paga, "DINHEIRO").andExpect(status().isOk());
        fechar(dia, "100", null, recepcao()).andExpect(status().isOk());

        UUID depois = cobranca();
        pagar(depois, "DINHEIRO").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("fechado")));
        estornar(paga).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("fechado")));
        assertThat(statusNoBanco(depois)).isEqualTo("PENDENTE");
        assertThat(statusNoBanco(paga)).isEqualTo("PAGA");
        fechar(dia, "100", null, recepcao()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("já está fechado")));
        abrir("0", recepcao()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("só o ADMIN reabre")));

        reabrir(dia, "Aluno chegou depois", recepcao()).andExpect(status().isForbidden());
        reabrir(dia, null, admin()).andExpect(status().isBadRequest());
        reabrir(dia, "  ", admin()).andExpect(status().isBadRequest());
        reabrir(dia, "Aluno chegou depois", admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("ABERTO"))
                .andExpect(jsonPath("$.reaberturas", hasSize(1)))
                .andExpect(jsonPath("$.reaberturas[0].motivo").value("Aluno chegou depois"))
                .andExpect(jsonPath("$.reaberturas[0].por").value(admin.toString()));
        assertThat(trilha(dia, "REABERTO")).startsWith(admin.toString()).contains("Aluno chegou depois");
        reabrir(dia, "De novo", admin()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("já está aberto")));

        pagar(depois, "DINHEIRO").andExpect(status().isOk());
        // O primeiro fechamento fica no histórico; o novo conta o dia inteiro.
        fechar(dia, "200", null, recepcao())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechamentos", hasSize(2)))
                .andExpect(jsonPath("$.fechamentos[0].esperado").value(100.00))
                .andExpect(jsonPath("$.fechamentos[1].esperado").value(200.00));
    }

    @Test
    void diferencaGravadaComJustificativa() throws Exception {
        abrir("30", recepcao()).andExpect(status().isCreated());
        pagar(cobranca(), "DINHEIRO").andExpect(status().isOk());

        fechar(dia, "120", null, recepcao()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("-10.00")));
        fechar(dia, "120", "   ", recepcao()).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT situacao FROM caixas WHERE dia = ?", String.class, dia)).isEqualTo("ABERTO");

        fechar(dia, "120", "Dei R$ 10 de troco a mais", recepcao())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechamentos[0].esperado").value(130.00))
                .andExpect(jsonPath("$.fechamentos[0].diferenca").value(-10.00))
                .andExpect(jsonPath("$.fechamentos[0].justificativa").value("Dei R$ 10 de troco a mais"));
        assertThat(trilha(dia, "FECHADO")).contains("-10.00").contains("Dei R$ 10 de troco a mais");
    }

    /** O dinheiro do estorno sai do caixa do dia em que é feito, não do dia do pagamento. */
    @Test
    void estornoSaiDoCaixaDoDiaDoEstorno() throws Exception {
        abrir("0", recepcao()).andExpect(status().isCreated());
        UUID id = cobranca();
        pagar(id, "DINHEIRO").andExpect(status().isOk());
        fechar(dia, "100", null, recepcao()).andExpect(status().isOk());

        irPara(dia.plusDays(1));
        abrir("50", admin()).andExpect(status().isCreated());
        estornar(id).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT caixa_id FROM lancamentos WHERE cobranca_id = ? AND tipo = 'ESTORNO'",
                UUID.class, id)).isEqualTo(caixaDoDia(dia.plusDays(1)));
        fechar(dia.plusDays(1), "0", "Estorno do pagamento de ontem; troco usado", recepcao())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fechamentos[0].esperado").value(-50.00))
                .andExpect(jsonPath("$.fechamentos[0].diferenca").value(50.00));
    }

    /**
     * Dia anterior esquecido aberto: hoje só abre depois de fechá-lo. O ADMIN pode reabrir um
     * dia passado (para recontar), mas o pagamento entra sempre no caixa de hoje, e o dia
     * seguinte só abre depois de fechá-lo de novo (decisão do dono).
     */
    @Test
    void hojeSoAbreDepoisDeFecharOAnterior() throws Exception {
        abrir("10", recepcao()).andExpect(status().isCreated());

        LocalDate amanha = dia.plusDays(1);
        irPara(amanha);
        abrir("10", recepcao()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString(dia.toString())));
        UUID id = cobranca();
        pagar(id, "DINHEIRO").andExpect(status().isConflict());

        // A recepção fecha o dia passado com a contagem.
        fechar(dia, "10", null, recepcao()).andExpect(status().isOk());
        abrir("10", recepcao()).andExpect(status().isCreated());

        reabrir(dia, "Recontar a gaveta", admin()).andExpect(status().isOk());
        pagar(id, "DINHEIRO").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT caixa_id FROM lancamentos WHERE cobranca_id = ?", UUID.class, id))
                .isEqualTo(caixaDoDia(amanha));
        fechar(amanha, "110", null, recepcao()).andExpect(status().isOk());

        irPara(dia.plusDays(2));
        abrir("10", recepcao()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString(dia.toString())));
        fechar(dia, "12", "Achei R$ 2 na gaveta", recepcao()).andExpect(status().isOk())
                .andExpect(jsonPath("$.fechamentos", hasSize(2)))
                .andExpect(jsonPath("$.fechamentos[1].esperado").value(10.00));
        abrir("10", recepcao()).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "100000.01", "1.001", "\"abc\""})
    void trocoInvalido400(String troco) throws Exception {
        abrir(troco, recepcao()).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM caixas WHERE dia = ?", Integer.class, dia)).isZero();
    }

    @Test
    void fechamentoInvalido() throws Exception {
        fechar(dia, "0", null, recepcao()).andExpect(status().isNotFound());
        mvc.perform(get("/api/caixas/" + dia).with(recepcao())).andExpect(status().isNotFound());
        reabrir(dia, "Engano", admin()).andExpect(status().isNotFound());
        abrir("0", recepcao()).andExpect(status().isCreated());
        fechar(dia, "null", null, recepcao()).andExpect(status().isBadRequest());
        fechar(dia, "-1", "x", recepcao()).andExpect(status().isBadRequest());
        mvc.perform(post("/api/caixas/31-12-2026/fechar").with(recepcao()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contado\": 0}")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSTRUTOR", "ALUNO"})
    void soAEquipeDoBalcao(String perfil) throws Exception {
        RequestPostProcessor quem = como(perfil, UUID.randomUUID());
        abrir("0", quem).andExpect(status().isForbidden());
        abrir("0", recepcao()).andExpect(status().isCreated());
        mvc.perform(get("/api/caixas/" + dia).with(quem)).andExpect(status().isForbidden());
        fechar(dia, "0", null, quem).andExpect(status().isForbidden());
        reabrir(dia, "x", quem).andExpect(status().isForbidden());
        mvc.perform(get("/api/caixas/" + dia).with(recepcao())).andExpect(status().isOk());
    }

    @Test
    void semTokenNaoVe() throws Exception {
        mvc.perform(get("/api/caixas/" + dia)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/caixas").contentType(MediaType.APPLICATION_JSON).content("{\"troco\": 0}"))
                .andExpect(status().isUnauthorized());
    }

    /** Duas aberturas ao mesmo tempo: uma abre, a outra recebe 409 (não 500). */
    @Test
    void duasAberturasAoMesmoTempo() throws Exception {
        for (int rodada = 0; rodada < 10; rodada++) {
            LocalDate data = dia.plusDays(rodada);
            irPara(data);
            List<Integer> resultados = correr(() -> abrir("0", recepcao()), () -> abrir("0", admin()));
            assertThat(resultados).as("rodada %d", rodada).containsExactly(201, 409);
            fechar(data, "0", null, recepcao()).andExpect(status().isOk());
        }
    }

    /**
     * Fechar e pagar em dinheiro ao mesmo tempo: ou o pagamento entra antes e está no esperado,
     * ou chega depois e é recusado. Nunca um pagamento num caixa já fechado, fora da conta.
     * Cada lado precisa ganhar em parte das rodadas, senão a corrida não foi testada.
     */
    @Test
    void fecharEPagarAoMesmoTempo() throws Exception {
        Set<String> vencedores = new HashSet<>();
        for (int rodada = 0; rodada < 30; rodada++) {
            LocalDate data = dia.plusDays(rodada);
            irPara(data);
            abrir("10", recepcao()).andExpect(status().isCreated());
            UUID id = cobranca();

            List<Integer> resultados = correr(() -> pagar(id, "DINHEIRO"),
                    () -> fechar(data, "10", "Conferência da corrida", recepcao()));

            BigDecimal esperado = jdbc.queryForObject("SELECT esperado FROM fechamentos_de_caixa f JOIN caixas c "
                    + "ON c.id = f.caixa_id WHERE c.dia = ?", BigDecimal.class, data);
            BigDecimal dinheiroNoCaixa = jdbc.queryForObject("SELECT coalesce(sum(total), 0) FROM lancamentos "
                    + "WHERE caixa_id = ?", BigDecimal.class, caixaDoDia(data));
            assertThat(esperado).as("rodada %d", rodada).isEqualByComparingTo(dinheiroNoCaixa.add(BigDecimal.TEN));
            if (resultados.equals(List.of(200, 200))) {
                vencedores.add("pagamento");
                assertThat(statusNoBanco(id)).isEqualTo("PAGA");
            } else {
                assertThat(resultados).as("rodada %d", rodada).containsExactly(200, 409);
                vencedores.add("fechamento");
                assertThat(statusNoBanco(id)).isEqualTo("PENDENTE");
                assertThat(lancamentos(id)).isZero();
            }
        }
        assertThat(vencedores).containsExactlyInAnyOrder("pagamento", "fechamento");
    }

    /**
     * A recepção vê hoje e os dias ainda abertos; com o caixa aberto, a contagem é às cegas:
     * sem a forma DINHEIRO nem os fechamentos anteriores (decisões do dono). O ADMIN vê tudo.
     */
    @Test
    void recepcaoVeSoHojeEOsDiasAbertosComAContagemAsCegas() throws Exception {
        abrir("50", recepcao())
                .andExpect(jsonPath("$.dinheiroOculto").value(true))
                .andExpect(jsonPath("$.movimento.formas[*].forma").value(contains("PIX", "CARTAO_DEBITO", "CARTAO_CREDITO")));
        pagar(cobranca(), "DINHEIRO").andExpect(status().isOk());
        pagar(cobranca(), "PIX").andExpect(status().isOk());

        mvc.perform(get("/api/caixas/" + dia).with(recepcao()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dinheiroOculto").value(true))
                .andExpect(jsonPath("$.movimento.formas[*].forma").value(contains("PIX", "CARTAO_DEBITO", "CARTAO_CREDITO")))
                .andExpect(jsonPath("$.movimento.formas[0].recebido").value(100.00))
                .andExpect(jsonPath("$.movimento.total.recebido").value(100.00))
                .andExpect(jsonPath("$.movimento.total.pagamentos").value(1));
        mvc.perform(get("/api/caixas/" + dia).with(admin()))
                .andExpect(jsonPath("$.dinheiroOculto").value(false))
                .andExpect(jsonPath("$.movimento.formas[*].forma")
                        .value(contains("DINHEIRO", "PIX", "CARTAO_DEBITO", "CARTAO_CREDITO")))
                .andExpect(jsonPath("$.movimento.formas[0].recebido").value(100.00))
                .andExpect(jsonPath("$.movimento.total.recebido").value(200.00))
                .andExpect(jsonPath("$.movimento.total.liquido").value(200.00));

        // Quem fecha vê o resultado; depois de fechado, hoje continua visível e completo.
        fechar(dia, "140", "Faltou troco", recepcao())
                .andExpect(jsonPath("$.dinheiroOculto").value(false))
                .andExpect(jsonPath("$.fechamentos[0].diferenca").value(-10.00));
        mvc.perform(get("/api/caixas/" + dia).with(recepcao()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movimento.formas[0].forma").value("DINHEIRO"))
                .andExpect(jsonPath("$.fechamentos[0].justificativa").value("Faltou troco"));

        // No dia seguinte, o fechado de ontem é só do ADMIN.
        irPara(dia.plusDays(1));
        mvc.perform(get("/api/caixas/" + dia).with(recepcao())).andExpect(status().isForbidden());
        mvc.perform(get("/api/caixas/" + dia).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/caixas/abertos").with(recepcao()))
                .andExpect(jsonPath("$[?(@.dia == '" + dia + "')]", hasSize(0)));

        // Reaberto, volta a ser da recepção, às cegas: sem o fechamento anterior (com o esperado).
        reabrir(dia, "Recontar", admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.dinheiroOculto").value(false))
                .andExpect(jsonPath("$.fechamentos", hasSize(1)));
        mvc.perform(get("/api/caixas/" + dia).with(recepcao()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dinheiroOculto").value(true))
                .andExpect(jsonPath("$.fechamentos", hasSize(0)))
                .andExpect(jsonPath("$.reaberturas[0].motivo").value("Recontar"));
        mvc.perform(get("/api/caixas/abertos").with(recepcao()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.dia == '" + dia + "')]", hasSize(1)));
        mvc.perform(get("/api/caixas/abertos").with(como("INSTRUTOR", UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    /**
     * O relatório do período (só o ADMIN): cada dia por forma, menos os estornos (no dia em que
     * foram feitos), com os fechamentos, o total do período e a soma das diferenças.
     */
    @Test
    void relatorioDoPeriodo() throws Exception {
        abrir("50", recepcao()).andExpect(status().isCreated());
        UUID emDinheiro = cobranca();
        pagar(emDinheiro, "DINHEIRO").andExpect(status().isOk());
        pagar(cobranca(), "PIX").andExpect(status().isOk());
        UUID noDebito = cobranca();
        pagar(noDebito, "CARTAO_DEBITO").andExpect(status().isOk());
        estornar(noDebito).andExpect(status().isOk());
        fechar(dia, "145", "Faltaram 5", recepcao()).andExpect(status().isOk());

        LocalDate segundo = dia.plusDays(1);
        irPara(segundo);
        abrir("20", recepcao()).andExpect(status().isCreated());
        estornar(emDinheiro).andExpect(status().isOk());
        pagar(cobranca(), "CARTAO_CREDITO").andExpect(status().isOk());
        fechar(segundo, "0", "Estorno de ontem saiu da gaveta", recepcao()).andExpect(status().isOk());
        reabrir(segundo, "Recontar", admin()).andExpect(status().isOk());
        fechar(segundo, "1", "Recontado: achei R$ 1", recepcao()).andExpect(status().isOk());

        // Um dia de antes do caixa (V35): lançamento sem caixa, pelo dia do registro.
        LocalDate antigo = dia.plusDays(3);
        lancamentoSemCaixa(cobranca(), antigo.atTime(23, 30).atZone(Calendario.FUSO).toInstant());

        // Um dia fechado com diferença e reaberto: ainda sem a contagem final, fica fora da soma.
        LocalDate quarto = dia.plusDays(4);
        irPara(quarto);
        abrir("0", recepcao()).andExpect(status().isCreated());
        fechar(quarto, "7", "Sobrou", recepcao()).andExpect(status().isOk());
        reabrir(quarto, "Recontar", admin()).andExpect(status().isOk());

        LocalDate terceiro = dia.plusDays(2);
        irPara(dia.plusDays(5));
        mvc.perform(get("/api/caixas/relatorio").param("de", quarto.toString()).param("ate", quarto.toString())
                        .with(admin()))
                .andExpect(jsonPath("$.dias[0].situacao").value("ABERTO"))
                .andExpect(jsonPath("$.dias[0].fechamentos[0].diferenca").value(7.00))
                .andExpect(jsonPath("$.diferencaDosFechados").value(0.00));
        mvc.perform(get("/api/caixas/relatorio").param("de", dia.toString()).param("ate", antigo.toString())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias[*].dia").value(contains(dia.toString(), segundo.toString(), antigo.toString())))
                .andExpect(jsonPath("$.dias[0].situacao").value("FECHADO"))
                .andExpect(jsonPath("$.dias[0].troco").value(50.00))
                .andExpect(jsonPath("$.dias[0].movimento.formas[0].recebido").value(100.00))
                .andExpect(jsonPath("$.dias[0].movimento.formas[1].recebido").value(100.00))
                .andExpect(jsonPath("$.dias[0].movimento.formas[2].recebido").value(100.00))
                .andExpect(jsonPath("$.dias[0].movimento.formas[2].estornado").value(100.00))
                .andExpect(jsonPath("$.dias[0].movimento.formas[2].liquido").value(0.00))
                .andExpect(jsonPath("$.dias[0].movimento.total.liquido").value(200.00))
                .andExpect(jsonPath("$.dias[0].fechamentos[0].esperado").value(150.00))
                .andExpect(jsonPath("$.dias[0].fechamentos[0].diferenca").value(-5.00))
                .andExpect(jsonPath("$.dias[1].movimento.formas[0].estornado").value(100.00))
                .andExpect(jsonPath("$.dias[1].movimento.formas[0].liquido").value(-100.00))
                .andExpect(jsonPath("$.dias[1].movimento.formas[3].recebido").value(100.00))
                .andExpect(jsonPath("$.dias[1].movimento.total.liquido").value(0.00))
                .andExpect(jsonPath("$.dias[1].fechamentos", hasSize(2)))
                .andExpect(jsonPath("$.dias[1].reaberturas[0].motivo").value("Recontar"))
                .andExpect(jsonPath("$.dias[2].situacao").value(nullValue()))
                .andExpect(jsonPath("$.dias[2].fechamentos", hasSize(0)))
                .andExpect(jsonPath("$.dias[2].movimento.formas[1].recebido").value(100.00))
                .andExpect(jsonPath("$.total.formas[0].liquido").value(0.00))
                .andExpect(jsonPath("$.total.formas[1].liquido").value(200.00))
                .andExpect(jsonPath("$.total.total.recebido").value(500.00))
                .andExpect(jsonPath("$.total.total.estornado").value(200.00))
                .andExpect(jsonPath("$.total.total.pagamentos").value(5))
                .andExpect(jsonPath("$.total.total.estornos").value(2))
                .andExpect(jsonPath("$.total.total.liquido").value(300.00))
                // −5 do primeiro dia + a do último fechamento do segundo (contado 1, esperado 20 − 100 = −80):
                // +81. O primeiro fechamento do segundo dia (+80) não conta: foi refeito.
                .andExpect(jsonPath("$.diferencaDosFechados").value(76.00));

        // Um dia só, e um período sem nada.
        mvc.perform(get("/api/caixas/relatorio").param("de", terceiro.toString()).param("ate", terceiro.toString())
                .with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.dias", hasSize(0)))
                .andExpect(jsonPath("$.total.total.liquido").value(0.00))
                .andExpect(jsonPath("$.diferencaDosFechados").value(0.00));
        mvc.perform(get("/api/caixas/relatorio").param("de", segundo.toString()).param("ate", segundo.toString())
                .with(admin())).andExpect(jsonPath("$.dias[*].dia").value(contains(segundo.toString())));
    }

    @Test
    void relatorioSoDoAdminEComPeriodoValido() throws Exception {
        mvc.perform(get("/api/caixas/relatorio").param("de", dia.toString()).param("ate", dia.toString())
                .with(recepcao())).andExpect(status().isForbidden());
        mvc.perform(get("/api/caixas/relatorio").param("de", dia.toString()).param("ate", dia.plusDays(365).toString())
                .with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/caixas/relatorio").param("de", dia.toString()).param("ate", dia.plusDays(366).toString())
                .with(admin())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("366")));
        mvc.perform(get("/api/caixas/relatorio").param("de", dia.toString()).param("ate", dia.minusDays(1).toString())
                .with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/caixas/relatorio").param("de", dia.toString()).with(admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/caixas/relatorio").param("de", "ontem").param("ate", dia.toString()).with(admin()))
                .andExpect(status().isBadRequest());
    }

    /**
     * Grava um lançamento sem caixa, como os de antes da V35. A trava da V35 vale só para as
     * linhas novas; aqui ela sai e volta na mesma transação.
     */
    private void lancamentoSemCaixa(UUID cobranca, java.time.Instant registradoEm) {
        new TransactionTemplate(transacoes).executeWithoutResult(t -> {
            jdbc.execute("ALTER TABLE lancamentos DROP CONSTRAINT ck_lancamentos_no_caixa");
            jdbc.update("""
                    INSERT INTO lancamentos (id, cobranca_id, tipo, valor, acrescimo, juros, total, meio_de_pagamento,
                                             registrado_em, registrado_por)
                    VALUES (?, ?, 'PAGAMENTO', 100, 0, 0, 100, 'PIX', ?, ?)""", UUID.randomUUID(), cobranca,
                    java.sql.Timestamp.from(registradoEm), recepcao);
            jdbc.execute("ALTER TABLE lancamentos ADD CONSTRAINT ck_lancamentos_no_caixa "
                    + "CHECK (caixa_id IS NOT NULL) NOT VALID");
        });
    }

    /** As travas da V35 também para quem grava sem passar pelo Java. */
    @Test
    void bancoRecusaLancamentoForaDoCaixaAberto() throws Exception {
        abrir("0", recepcao()).andExpect(status().isCreated());
        UUID caixa = caixaDoDia(dia);
        UUID id = cobranca();
        String insere = """
                INSERT INTO lancamentos (id, cobranca_id, tipo, valor, acrescimo, juros, total, meio_de_pagamento,
                                         registrado_em, registrado_por, caixa_id)
                VALUES (?, ?, 'PAGAMENTO', 100, 0, 0, 100, 'PIX', ?::timestamptz, ?, ?)""";
        String meioDia = dia + "T12:00:00-03:00";

        assertThatThrownBy(() -> jdbc.update(insere, UUID.randomUUID(), id, meioDia, recepcao, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_lancamentos_no_caixa");
        assertThatThrownBy(() -> jdbc.update(insere, UUID.randomUUID(), id, dia.plusDays(1) + "T12:00:00-03:00",
                recepcao, caixa)).isInstanceOf(DataAccessException.class).hasMessageContaining("caixa do dia");
        // 00:30 em São Paulo é 03:30 UTC: o dia é o da academia, não o do servidor.
        assertThatThrownBy(() -> jdbc.update(insere, UUID.randomUUID(), id, dia.plusDays(1) + "T00:30:00-03:00",
                recepcao, caixa)).isInstanceOf(DataAccessException.class).hasMessageContaining("caixa do dia");
        jdbc.update(insere, UUID.randomUUID(), id, dia + "T23:30:00-03:00", recepcao, caixa);

        jdbc.update("UPDATE caixas SET situacao = 'FECHADO' WHERE id = ?", caixa);
        assertThatThrownBy(() -> jdbc.update(insere, UUID.randomUUID(), id, meioDia, recepcao, caixa))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("caixa aberto");
    }

    @Test
    void bancoRecusaCaixaEFechamentoIncoerentes() throws Exception {
        abrir("0", recepcao()).andExpect(status().isCreated());
        UUID caixa = caixaDoDia(dia);

        for (String mudanca : List.of("troco = 1", "dia = dia + 1", "aberto_por = '" + admin + "'",
                "aberto_em = aberto_em + interval '1 hour'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE caixas SET " + mudanca + " WHERE id = ?", caixa)).as(mudanca)
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("só muda a situação");
        }
        String fechamento = """
                INSERT INTO fechamentos_de_caixa (id, caixa_id, contado, esperado, diferenca, justificativa, fechado_em,
                                                  fechado_por)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?)""";
        assertThatThrownBy(() -> jdbc.update(fechamento, UUID.randomUUID(), caixa, 10, 10, 1, "x", recepcao))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_fechamentos_diferenca");
        assertThatThrownBy(() -> jdbc.update(fechamento, UUID.randomUUID(), caixa, 10, 0, 10, null, recepcao))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_fechamentos_diferenca_justificada");
        assertThatThrownBy(() -> jdbc.update(fechamento, UUID.randomUUID(), caixa, 10, 0, 10, " ", recepcao))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_fechamentos_justificativa");
        assertThatThrownBy(() -> jdbc.update(fechamento, UUID.randomUUID(), caixa, -1, -1, 0, null, recepcao))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fechamentos_de_caixa_contado_check");
        jdbc.update(fechamento, UUID.randomUUID(), caixa, 10, 0, 10, "Sobra", recepcao);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO reaberturas_de_caixa (id, caixa_id, motivo, reaberto_em, "
                + "reaberto_por) VALUES (?, ?, ' ', now(), ?)", UUID.randomUUID(), caixa, admin))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("reaberturas_de_caixa_motivo_check");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO caixas (id, dia, troco, situacao, aberto_em, aberto_por) "
                + "VALUES (?, ?, 0, 'ABERTO', now(), ?)", UUID.randomUUID(), dia, recepcao))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("caixas_dia_key");
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
                    return chamada.fazer().andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    return -1;
                }
            }));
        }
        largada.countDown();
        return lados.stream().map(CompletableFuture::join).sorted().toList();
    }
}
