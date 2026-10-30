package br.com.academiagestao.cobranca;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.Cronograma;
import br.com.academiagestao.cobranca.dominio.MatriculaParaCobranca;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.RegrasDeAtraso;
import br.com.academiagestao.cobranca.dominio.StatusCobranca;
import br.com.academiagestao.compartilhado.Calendario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gravação e leitura das cobranças com SQL (o domínio não tem JPA, ADR 0024). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CobrancaRepositoryTest {

    @Autowired
    CobrancaRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    private final UUID aluno = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID matricula = UUID.randomUUID();
    private final UUID usuario = UUID.randomUUID();
    private final LocalDate inicio = LocalDate.of(2026, 10, 17);
    private UUID caixa;

    @BeforeEach
    void preparar() {
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 2)", plano, "P " + plano);
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'R', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'P', 100, 2, ?, ?, now())""", matricula, aluno, plano, inicio, inicio.plusMonths(2).minusDays(1));
        // Os lançamentos gravados aqui são de agora: entram no caixa de hoje (gatilho da V35).
        caixa = CaixaDeTeste.abrir(jdbc, Calendario.hoje(Instant.now()), usuario);
    }

    @AfterEach
    void limpar() {
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("planos", plano);
        limpeza.apagar("caixas", caixa);
        limpeza.apagar("usuarios", usuario);
    }

    /** Um código de Pix novo: o Pix nunca repete entre as pagas, e o banco é dividido. */
    private static String pix() {
        return "E2E" + UUID.randomUUID();
    }

    private List<Cobranca> gravarCronograma() {
        var dados = new MatriculaParaCobranca(matricula, aluno, new BigDecimal("100"), 2, false, inicio, List.of(), null);
        Cronograma.acertar(dados, List.of(), inicio, Instant.now()).novas().forEach(repository::inserir);
        return repository.travarDaMatricula(matricula);
    }

    @Test
    void gravaELeIgual() {
        Cobranca lida = gravarCronograma().getFirst();
        lida.pagar(RegrasDeAtraso.PADRAO, inicio, new BigDecimal("100"), MeioDePagamento.PIX, pix(), usuario,
                Instant.parse("2026-10-17T15:00:00.123456Z"));
        repository.atualizar(lida);

        Cobranca relida = repository.travarDaMatricula(matricula).getFirst();
        assertThat(relida.getStatus()).isEqualTo(StatusCobranca.PAGA);
        assertThat(relida.getPagamento()).isEqualTo(lida.getPagamento());
        assertThat(relida.getVencimento()).isEqualTo(inicio);
        assertThat(relida.getValor()).isEqualByComparingTo("100");
        assertThat(relida.getVersao()).isEqualTo(1);

        relida.estornar(inicio.plusDays(40), usuario, Instant.parse("2026-10-18T09:00:00Z"));
        relida.cancelar("Engano", usuario, Instant.parse("2026-10-18T10:00:00Z"));
        repository.atualizar(relida);
        Cobranca cancelada = repository.travarDaMatricula(matricula).getFirst();
        assertThat(cancelada.getPagamento()).isNull();
        assertThat(cancelada.getCancelamento()).isEqualTo(relida.getCancelamento());
        assertThat(cancelada.getEstornadaNoDia()).isEqualTo(inicio.plusDays(40));
    }

    /** A marca de fora do cronograma é gravada, e o banco nunca a aceita numa em aberto (ADR 0026). */
    @Test
    void foraDoCronogramaGravadoENuncaEmAberto() {
        List<Cobranca> gravadas = gravarCronograma();
        Cobranca pagaAdiantada = gravadas.get(1);
        pagaAdiantada.pagar(RegrasDeAtraso.PADRAO, inicio, new BigDecimal("100"), MeioDePagamento.PIX, pix(), usuario,
                Instant.parse("2026-10-17T15:00:00Z"));
        repository.atualizar(pagaAdiantada);
        var cancelada = new MatriculaParaCobranca(matricula, aluno, new BigDecimal("100"), 2, false, inicio, List.of(),
                new MatriculaParaCobranca.Encerramento(MatriculaParaCobranca.Encerramento.Tipo.CANCELAMENTO, inicio, null));
        Cronograma.acertar(cancelada, repository.travarDaMatricula(matricula), inicio, Instant.now())
                .alteradas().forEach(repository::atualizar);

        assertThat(repository.travar(pagaAdiantada.getId()).orElseThrow().isForaDoCronograma()).isTrue();
        assertThat(repository.travar(gravadas.getFirst().getId()).orElseThrow().isForaDoCronograma()).isFalse();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE cobrancas SET status = 'PENDENTE', pago_acrescimo = NULL, pago_juros = NULL, pago_total = NULL,
                    meio_de_pagamento = NULL, codigo_transacao = NULL, paga_em = NULL, paga_por = NULL WHERE id = ?""",
                pagaAdiantada.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cobrancas_fora_do_cronograma");
    }

    /** A coluna gerada {@code prazo} faz a mesma conta de {@code Cobranca.getPrazo()} (ADR 0026). */
    @Test
    void prazoDoBancoIgualAoDoDominio() {
        var tresMeses = new MatriculaParaCobranca(matricula, aluno, new BigDecimal("100"), 3, false, inicio,
                List.of(), null);
        Cronograma.acertar(tresMeses, List.of(), inicio, Instant.now()).novas().forEach(repository::inserir);
        List<Cobranca> cronograma = repository.travarDaMatricula(matricula);
        Cobranca semEstorno = cronograma.getFirst();
        Cobranca estornadaDepois = cronograma.get(1);
        Cobranca estornadaAntes = cronograma.get(2);
        for (Cobranca c : List.of(estornadaDepois, estornadaAntes)) {
            c.pagar(RegrasDeAtraso.PADRAO, inicio, c.getValor(), MeioDePagamento.PIX, pix(), usuario,
                    Instant.parse("2026-10-17T15:00:00Z"));
        }
        estornadaDepois.estornar(estornadaDepois.getVencimento().plusDays(45), usuario, Instant.now());
        estornadaAntes.estornar(estornadaAntes.getVencimento().minusDays(3), usuario, Instant.now());
        repository.atualizar(estornadaDepois);
        repository.atualizar(estornadaAntes);

        for (Cobranca lida : repository.travarDaMatricula(matricula)) {
            LocalDate doBanco = jdbc.queryForObject("SELECT prazo FROM cobrancas WHERE id = ?", LocalDate.class,
                    lida.getId());
            assertThat(doBanco).as("parcela %d", lida.getParcela()).isEqualTo(lida.getPrazo());
        }
        assertThat(repository.travar(semEstorno.getId()).orElseThrow().getPrazo())
                .isEqualTo(semEstorno.getVencimento());
        assertThat(repository.travar(estornadaDepois.getId()).orElseThrow().getPrazo())
                .isEqualTo(estornadaDepois.getVencimento().plusDays(45));
        assertThat(repository.travar(estornadaAntes.getId()).orElseThrow().getPrazo())
                .isEqualTo(estornadaAntes.getVencimento());
    }

    /**
     * O pior caso do teto (V23): à vista de R$ 100.000 × 60 meses, paga com 20 anos de atraso
     * e juros no máximo (10% ao mês). O total passa dos 99.999.999,99 que a coluna antiga guardava.
     */
    @Test
    void pagamentoDoMaiorAVistaComAnosDeAtrasoCabe() {
        jdbc.update("UPDATE matriculas SET valor_mensal = 100000, duracao_meses = 60, fim = ? WHERE id = ?",
                inicio.plusMonths(60).minusDays(1), matricula);
        var dados = new MatriculaParaCobranca(matricula, aluno, new BigDecimal("100000"), 60, true, inicio, List.of(),
                null);
        Cronograma.acertar(dados, List.of(), inicio, Instant.now()).novas().forEach(repository::inserir);
        Cobranca aVista = repository.travarDaMatricula(matricula).getFirst();
        assertThat(aVista.getValor()).isEqualByComparingTo("6000000");

        var regras = new RegrasDeAtraso(RegrasDeAtraso.PADRAO.tipoAcrescimo(), RegrasDeAtraso.PADRAO.acrescimo(),
                RegrasDeAtraso.JUROS_MAXIMOS_AO_MES, 10);
        LocalDate vinteAnosDepois = inicio.plusYears(20);
        BigDecimal devido = regras.devido(aVista, vinteAnosDepois).total();
        aVista.pagar(regras, vinteAnosDepois, devido, MeioDePagamento.PIX, pix(), usuario, Instant.now());
        repository.atualizar(aVista);

        assertThat(repository.travarDaMatricula(matricula).getFirst().getPagamento().total())
                .isEqualByComparingTo(devido).isGreaterThan(new BigDecimal("99999999.99"));
    }

    private static final String LANCAMENTO = """
            INSERT INTO lancamentos (id, cobranca_id, tipo, estorna, valor, acrescimo, juros, total, meio_de_pagamento,
                                     codigo_transacao, registrado_em, registrado_por, motivo, caixa_id)
            VALUES (?, ?, ?, ?, 100, 2, 1, ?, 'PIX', 'E2E1', now(), ?, ?, ?)""";

    /** As travas do registro (V24), também para quem grava sem passar pelo Java. */
    @Test
    void bancoRecusaLancamentoIncoerente() {
        List<Cobranca> cobrancas = gravarCronograma();
        UUID primeira = cobrancas.get(0).getId();
        UUID segunda = cobrancas.get(1).getId();
        UUID pagamento = UUID.randomUUID();
        jdbc.update(LANCAMENTO, pagamento, primeira, "PAGAMENTO", null, new BigDecimal("103"), usuario, null, caixa);

        // Total que não fecha; pagamento com motivo ou apontando para outro; estorno sem motivo.
        recusa("ck_lancamentos_total", primeira, "PAGAMENTO", null, "102", null);
        recusa("ck_lancamentos_estorno", primeira, "PAGAMENTO", null, "103", "Engano");
        recusa("ck_lancamentos_estorno", primeira, "PAGAMENTO", pagamento, "103", null);
        recusa("ck_lancamentos_estorno", primeira, "ESTORNO", pagamento, "103", null);
        recusa("ck_lancamentos_estorno", primeira, "ESTORNO", pagamento, "103", "  ");
        recusa("ck_lancamentos_estorno", primeira, "ESTORNO", null, "103", "Engano");
        // O estorno é do pagamento da mesma cobrança, e uma vez só.
        recusa("fk_lancamentos_estorno_da_mesma_cobranca", segunda, "ESTORNO", pagamento, "103", "Engano");
        jdbc.update(LANCAMENTO, UUID.randomUUID(), primeira, "ESTORNO", pagamento, new BigDecimal("103"), usuario,
                "Engano", caixa);
        recusa("lancamentos_estorna_key", primeira, "ESTORNO", pagamento, "103", "De novo");
    }

    /**
     * O código da transação (V54, ADR 0046, item 10): obrigatório no pagamento novo no Pix e no
     * cartão, limpo (sem espaços, em maiúsculas), e só nessas formas.
     */
    @Test
    void bancoRecusaCodigoDaTransacaoIncoerente() {
        UUID cobranca = gravarCronograma().getFirst().getId();
        String insere = """
                INSERT INTO lancamentos (id, cobranca_id, tipo, valor, acrescimo, juros, total, meio_de_pagamento,
                                         codigo_transacao, registrado_em, registrado_por, caixa_id)
                VALUES (?, ?, 'PAGAMENTO', 100, 0, 0, 100, ?, ?, now(), ?, ?)""";

        for (String[] caso : new String[][]{{"PIX", null, "ck_lancamentos_codigo_obrigatorio"},
                {"CARTAO_CREDITO", null, "ck_lancamentos_codigo_obrigatorio"},
                {"PIX", "", "ck_lancamentos_codigo_transacao"}, {"PIX", "e2e1", "ck_lancamentos_codigo_transacao"},
                {"PIX", "E2E 1", "ck_lancamentos_codigo_transacao"}, {"PIX", "E2E\t1", "ck_lancamentos_codigo_transacao"},
                {"DINHEIRO", "E2E1", "ck_lancamentos_codigo_transacao"}}) {
            assertThatThrownBy(() -> jdbc.update(insere, UUID.randomUUID(), cobranca, caso[0], caso[1], usuario, caixa))
                    .as("%s %s", caso[0], caso[1]).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(caso[2]);
        }
        jdbc.update(insere, UUID.randomUUID(), cobranca, "DINHEIRO", null, usuario, caixa);
        jdbc.update(insere, UUID.randomUUID(), cobranca, "CARTAO_DEBITO", "NSU-1", usuario, caixa);

        // Na cobrança, só no pagamento em vigor no Pix e no cartão, e limpo.
        String paga = """
                UPDATE cobrancas SET status = 'PAGA', pago_acrescimo = 0, pago_juros = 0, pago_total = 100,
                    meio_de_pagamento = ?, codigo_transacao = ?, paga_em = now(), paga_por = ? WHERE id = ?""";
        assertThatThrownBy(() -> jdbc.update("UPDATE cobrancas SET codigo_transacao = 'E2E1' WHERE id = ?", cobranca))
                .as("em aberto").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cobrancas_codigo_transacao");
        assertThatThrownBy(() -> jdbc.update(paga, "DINHEIRO", "E2E1", usuario, cobranca))
                .as("dinheiro").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cobrancas_codigo_transacao");
        assertThatThrownBy(() -> jdbc.update(paga, "PIX", "e2e1", usuario, cobranca))
                .as("minúsculas").isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cobrancas_codigo_transacao");
    }

    /**
     * Os índices únicos da V54 barram o código repetido mesmo sem a checagem do Java (a corrida):
     * o Pix entre as pagas; o cartão na mesma forma e no mesmo dia.
     */
    @Test
    void bancoRecusaCodigoRepetido() {
        List<Cobranca> cobrancas = gravarCronograma();
        UUID primeira = cobrancas.get(0).getId();
        UUID segunda = cobrancas.get(1).getId();
        String paga = """
                UPDATE cobrancas SET status = 'PAGA', pago_acrescimo = 0, pago_juros = 0, pago_total = 100,
                    meio_de_pagamento = ?, codigo_transacao = ?, paga_em = ?::timestamptz, paga_por = ? WHERE id = ?""";
        String codigo = pix().toUpperCase();

        jdbc.update(paga, "PIX", codigo, "2026-10-01T12:00:00-03:00", usuario, primeira);
        assertThatThrownBy(() -> jdbc.update(paga, "PIX", codigo, "2027-03-01T12:00:00-03:00", usuario, segunda))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_cobrancas_codigo_do_pix");

        jdbc.update(paga, "CARTAO_DEBITO", codigo, "2026-10-01T12:00:00-03:00", usuario, primeira);
        assertThatThrownBy(() -> jdbc.update(paga, "CARTAO_DEBITO", codigo, "2026-10-01T23:30:00-03:00", usuario, segunda))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_cobrancas_codigo_do_cartao_no_dia");
        // 00:30 em São Paulo é outro dia, embora em UTC ainda seja o mesmo (03:30): vale o fuso da academia.
        jdbc.update(paga, "CARTAO_DEBITO", codigo, "2026-10-02T00:30:00-03:00", usuario, segunda);
        jdbc.update(paga, "CARTAO_CREDITO", codigo, "2026-10-01T12:00:00-03:00", usuario, segunda);
    }

    private void recusa(String restricao, UUID cobranca, String tipo, UUID estorna, String total, String motivo) {
        assertThatThrownBy(() -> jdbc.update(LANCAMENTO, UUID.randomUUID(), cobranca, tipo, estorna,
                new BigDecimal(total), usuario, motivo, caixa)).as(restricao)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(restricao);
    }

    /** Duas gravações a partir da mesma leitura: a segunda não sobrescreve a primeira. */
    @Test
    void versaoVelhaNaoGrava() {
        Cobranca primeira = gravarCronograma().getFirst();
        Cobranca mesmaLeitura = repository.travarDaMatricula(matricula).getFirst();
        primeira.cancelar("Engano", usuario, Instant.now());
        repository.atualizar(primeira);

        mesmaLeitura.pagar(RegrasDeAtraso.PADRAO, inicio, new BigDecimal("100"), MeioDePagamento.PIX, pix(), usuario,
                Instant.now());
        assertThatThrownBy(() -> repository.atualizar(mesmaLeitura))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(repository.travarDaMatricula(matricula).getFirst().getStatus()).isEqualTo(StatusCobranca.CANCELADA);
    }

    @Test
    void semCronogramaSoAsQueNaoTem() {
        UUID inexistente = UUID.randomUUID();
        assertThat(repository.semCronograma(List.of(matricula, inexistente))).containsExactlyInAnyOrder(matricula, inexistente);
        repository.registrarCronograma(matricula, inicio);
        assertThat(repository.semCronograma(List.of(matricula, inexistente))).containsExactly(inexistente);
        assertThat(repository.aPartirDe(matricula)).contains(inicio);
        assertThat(repository.semCronograma(List.of())).isEmpty();
    }
}
