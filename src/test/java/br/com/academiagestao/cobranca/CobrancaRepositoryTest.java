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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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

    @BeforeEach
    void preparar() {
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 2)", plano, "P " + plano);
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'R', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'P', 100, 2, ?, ?, now())""", matricula, aluno, plano, inicio, inicio.plusMonths(2).minusDays(1));
    }

    @AfterEach
    void limpar() {
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("planos", plano);
        limpeza.apagar("usuarios", usuario);
    }

    private List<Cobranca> gravarCronograma() {
        var dados = new MatriculaParaCobranca(matricula, aluno, new BigDecimal("100"), 2, false, inicio, List.of(), null);
        Cronograma.acertar(dados, List.of(), inicio, Instant.now()).novas().forEach(repository::inserir);
        return repository.travarDaMatricula(matricula);
    }

    @Test
    void gravaELeIgual() {
        Cobranca lida = gravarCronograma().getFirst();
        lida.pagar(RegrasDeAtraso.PADRAO, inicio, new BigDecimal("100"), MeioDePagamento.PIX, usuario,
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
        pagaAdiantada.pagar(RegrasDeAtraso.PADRAO, inicio, new BigDecimal("100"), MeioDePagamento.PIX, usuario,
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
                    meio_de_pagamento = NULL, paga_em = NULL, paga_por = NULL WHERE id = ?""", pagaAdiantada.getId()))
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
            c.pagar(RegrasDeAtraso.PADRAO, inicio, c.getValor(), MeioDePagamento.PIX, usuario,
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
        aVista.pagar(regras, vinteAnosDepois, devido, MeioDePagamento.PIX, usuario, Instant.now());
        repository.atualizar(aVista);

        assertThat(repository.travarDaMatricula(matricula).getFirst().getPagamento().total())
                .isEqualByComparingTo(devido).isGreaterThan(new BigDecimal("99999999.99"));
    }

    private static final String LANCAMENTO = """
            INSERT INTO lancamentos (id, cobranca_id, tipo, estorna, valor, acrescimo, juros, total, meio_de_pagamento,
                                     registrado_em, registrado_por, motivo)
            VALUES (?, ?, ?, ?, 100, 2, 1, ?, 'PIX', now(), ?, ?)""";

    /** As travas do registro (V24), também para quem grava sem passar pelo Java. */
    @Test
    void bancoRecusaLancamentoIncoerente() {
        List<Cobranca> cobrancas = gravarCronograma();
        UUID primeira = cobrancas.get(0).getId();
        UUID segunda = cobrancas.get(1).getId();
        UUID pagamento = UUID.randomUUID();
        jdbc.update(LANCAMENTO, pagamento, primeira, "PAGAMENTO", null, new BigDecimal("103"), usuario, null);

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
                "Engano");
        recusa("lancamentos_estorna_key", primeira, "ESTORNO", pagamento, "103", "De novo");
    }

    private void recusa(String restricao, UUID cobranca, String tipo, UUID estorna, String total, String motivo) {
        assertThatThrownBy(() -> jdbc.update(LANCAMENTO, UUID.randomUUID(), cobranca, tipo, estorna,
                new BigDecimal(total), usuario, motivo)).as(restricao)
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(restricao);
    }

    /** Duas gravações a partir da mesma leitura: a segunda não sobrescreve a primeira. */
    @Test
    void versaoVelhaNaoGrava() {
        Cobranca primeira = gravarCronograma().getFirst();
        Cobranca mesmaLeitura = repository.travarDaMatricula(matricula).getFirst();
        primeira.cancelar("Engano", usuario, Instant.now());
        repository.atualizar(primeira);

        mesmaLeitura.pagar(RegrasDeAtraso.PADRAO, inicio, new BigDecimal("100"), MeioDePagamento.PIX, usuario,
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
