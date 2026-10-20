package br.com.academiagestao.matriculas;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.cobranca.CobrancasDasMatriculas;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A matrícula gera e acerta as cobranças dela a cada mudança, na mesma transação (ADR 0024). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CobrancasDaMatriculaTest {

    @Autowired
    MatriculaService service;

    @Autowired
    MatriculaRepository repository;

    @Autowired
    CobrancasDasMatriculas cobrancasDasMatriculas;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    private final List<UUID> alunos = new ArrayList<>();
    private final List<UUID> planos = new ArrayList<>();
    private UUID usuario;
    private LocalDate hoje;

    // Um dia fixo no futuro (meio-dia em São Paulo): "hoje" e os meses não dependem da data real.
    @BeforeEach
    void preparar() {
        LocalDate amanha = Calendario.hoje(Instant.now()).plusDays(1);
        relogio.fixar(amanha.atTime(12, 0).atZone(Calendario.FUSO).toInstant());
        hoje = amanha;
        usuario = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Recepção', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        alunos.forEach(id -> limpeza.apagar("alunos", id));
        planos.forEach(id -> limpeza.apagar("planos", id));
        limpeza.apagar("usuarios", usuario);
    }

    private UUID aluno() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", id, CpfsParaTeste.aleatorio());
        alunos.add(id);
        return id;
    }

    private UUID plano(int meses, String forma, String percentualMulta) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO planos (id, nome, valor_mensal, duracao_meses, fidelidade, percentual_multa, forma_pagamento)
                VALUES (?, ?, 100, ?, ?, ?, ?)""", id, "Plano " + id, meses, percentualMulta != null,
                percentualMulta == null ? null : new BigDecimal(percentualMulta), forma);
        planos.add(id);
        return id;
    }

    private List<Map<String, Object>> cobrancas(UUID matricula) {
        return jdbc.queryForList("""
                SELECT tipo, parcela, valor, vencimento, status, cancelada_por, motivo_cancelamento
                FROM cobrancas WHERE matricula_id = ? ORDER BY tipo DESC, parcela""", matricula);
    }

    private static LocalDate data(Object valor) {
        return ((Date) valor).toLocalDate();
    }

    /** Das mensalidades (e da à vista); a multa é conferida à parte. */
    private List<LocalDate> vencimentos(UUID matricula, String status) {
        return cobrancas(matricula).stream().filter(c -> c.get("status").equals(status))
                .filter(c -> !c.get("tipo").equals("MULTA"))
                .map(c -> data(c.get("vencimento"))).toList();
    }

    @Test
    void mensalGeraUmaPorMesNoDiaDoInicio() {
        Matricula matricula = service.matricular(aluno(), plano(3, "MENSAL", null), null);

        List<Map<String, Object>> cobrancas = cobrancas(matricula.getId());
        assertThat(cobrancas).hasSize(3).allSatisfy(c -> {
            assertThat(c.get("tipo")).isEqualTo("MENSALIDADE");
            assertThat((BigDecimal) c.get("valor")).isEqualByComparingTo("100");
            assertThat(c.get("status")).isEqualTo("PENDENTE");
        });
        assertThat(vencimentos(matricula.getId(), "PENDENTE"))
                .containsExactly(hoje, hoje.plusMonths(1), hoje.plusMonths(2));
        assertThat(jdbc.queryForObject("SELECT a_partir_de FROM cronogramas WHERE matricula_id = ?", LocalDate.class,
                matricula.getId())).isEqualTo(hoje);
        assertThat(jdbc.queryForObject("SELECT aluno_id FROM cobrancas WHERE matricula_id = ? LIMIT 1", UUID.class,
                matricula.getId())).isEqualTo(matricula.getAlunoId());
    }

    @Test
    void agendadaGeraDesdeOInicio() {
        LocalDate inicio = hoje.plusDays(20);
        Matricula matricula = service.matricular(aluno(), plano(2, "MENSAL", null), inicio);

        assertThat(vencimentos(matricula.getId(), "PENDENTE")).containsExactly(inicio, inicio.plusMonths(1));
    }

    @Test
    void aVistaGeraUmaComOTotal() {
        Matricula matricula = service.matricular(aluno(), plano(12, "A_VISTA", null), null);

        assertThat(cobrancas(matricula.getId())).singleElement().satisfies(c -> {
            assertThat(c.get("tipo")).isEqualTo("A_VISTA");
            assertThat((BigDecimal) c.get("valor")).isEqualByComparingTo("1200");
            assertThat(data(c.get("vencimento"))).isEqualTo(hoje);
        });
    }

    /** Trancamento empurra as seguintes; a volta antecipada desfaz a parte não usada. */
    @Test
    void trancamentoEmpurraEAVoltaDesfaz() {
        Matricula matricula = service.matricular(aluno(), plano(3, "MENSAL", null), null);

        service.trancar(matricula.getId(), hoje.plusDays(1), hoje.plusDays(31), "Viagem", usuario);
        assertThat(vencimentos(matricula.getId(), "PENDENTE"))
                .containsExactly(hoje, hoje.plusMonths(1).plusDays(30), hoje.plusMonths(2).plusDays(30));

        relogio.adiantar(Duration.ofDays(11));
        service.registrarVolta(matricula.getId(), usuario);
        assertThat(vencimentos(matricula.getId(), "PENDENTE"))
                .containsExactly(hoje, hoje.plusMonths(1).plusDays(10), hoje.plusMonths(2).plusDays(10));
    }

    /** Cancelamento: ficam as até hoje, as futuras são canceladas e a multa vira cobrança. */
    @Test
    void cancelamentoCancelaAsFuturasECobraAMulta() {
        Matricula matricula = service.matricular(aluno(), plano(12, "MENSAL", "10"), null);
        relogio.adiantar(Duration.ofDays(40));

        Matricula cancelada = service.cancelar(matricula.getId(), "Mudou de cidade", usuario);

        LocalDate dia = hoje.plusDays(40);
        assertThat(vencimentos(matricula.getId(), "PENDENTE")).containsExactly(hoje, hoje.plusMonths(1));
        assertThat(vencimentos(matricula.getId(), "CANCELADA")).hasSize(10);
        assertThat(cobrancas(matricula.getId())).filteredOn(c -> c.get("status").equals("CANCELADA"))
                .allSatisfy(c -> {
                    assertThat(c.get("cancelada_por")).isNull();
                    assertThat((String) c.get("motivo_cancelamento")).contains("Matrícula encerrada");
                });
        assertThat(cobrancas(matricula.getId())).filteredOn(c -> c.get("tipo").equals("MULTA")).singleElement()
                .satisfies(multa -> {
                    assertThat((BigDecimal) multa.get("valor")).isEqualByComparingTo(cancelada.getMulta());
                    assertThat(data(multa.get("vencimento"))).isEqualTo(dia);
                    assertThat(multa.get("status")).isEqualTo("PENDENTE");
                });
        assertThat(cancelada.getMulta()).isPositive();
    }

    /**
     * Cancelada no dia de um vencimento: a mensalidade do dia fica, porque o mês começou (é
     * usado, como na multa). Numa troca no mesmo dia, ela sairia (ver a troca).
     */
    @Test
    void canceladaNoDiaDoVencimentoFicaComAMensalidadeDoDia() {
        Matricula matricula = service.matricular(aluno(), plano(3, "MENSAL", null), null);
        relogio.fixar(hoje.plusMonths(1).atTime(12, 0).atZone(Calendario.FUSO).toInstant());

        service.cancelar(matricula.getId(), "Mudou de cidade", usuario);

        assertThat(vencimentos(matricula.getId(), "PENDENTE")).containsExactly(hoje, hoje.plusMonths(1));
        assertThat(vencimentos(matricula.getId(), "CANCELADA")).containsExactly(hoje.plusMonths(2));
    }

    /** Trocada no dia de um vencimento: a mensalidade do dia sai, a nova começa com a dela. */
    @Test
    void trocadaNoDiaDoVencimentoNaoFicaComAMensalidadeDoDia() {
        Matricula atual = service.matricular(aluno(), plano(3, "MENSAL", null), null);
        relogio.fixar(hoje.plusMonths(1).atTime(12, 0).atZone(Calendario.FUSO).toInstant());

        service.trocarPlano(atual.getId(), plano(2, "MENSAL", null), usuario);

        assertThat(vencimentos(atual.getId(), "PENDENTE")).containsExactly(hoje);
        assertThat(vencimentos(atual.getId(), "CANCELADA")).containsExactly(hoje.plusMonths(1), hoje.plusMonths(2));
    }

    @Test
    void canceladaAntesDeComecarNaoDeveNada() {
        Matricula matricula = service.matricular(aluno(), plano(3, "MENSAL", "10"), hoje.plusDays(10));

        service.cancelar(matricula.getId(), "Desistiu", usuario);

        assertThat(vencimentos(matricula.getId(), "PENDENTE")).isEmpty();
        assertThat(vencimentos(matricula.getId(), "CANCELADA")).hasSize(3);
    }

    /** Paga adiantada e depois cancelada continua paga: a devolução é por fora (decisão do dono). */
    @Test
    void pagaAdiantadaContinuaPaga() {
        Matricula matricula = service.matricular(aluno(), plano(3, "MENSAL", null), null);
        jdbc.update("""
                UPDATE cobrancas SET status = 'PAGA', pago_acrescimo = 0, pago_juros = 0, pago_total = valor,
                       meio_de_pagamento = 'PIX', paga_em = now(), paga_por = ?
                WHERE matricula_id = ? AND parcela = 3""", usuario, matricula.getId());

        service.cancelar(matricula.getId(), "Desistiu", usuario);

        assertThat(vencimentos(matricula.getId(), "PAGA")).containsExactly(hoje.plusMonths(2));
    }

    /** Troca: a atual fica com as de antes do dia, e a nova começa com a primeira dela hoje. */
    @Test
    void trocaEncerraAAtualEGeraANova() {
        UUID aluno = aluno();
        Matricula atual = service.matricular(aluno, plano(3, "MENSAL", null), null);
        relogio.adiantar(Duration.ofDays(35));

        Matricula nova = service.trocarPlano(atual.getId(), plano(2, "MENSAL", null), usuario);

        LocalDate dia = hoje.plusDays(35);
        assertThat(vencimentos(atual.getId(), "PENDENTE")).containsExactly(hoje, hoje.plusMonths(1));
        assertThat(vencimentos(atual.getId(), "CANCELADA")).containsExactly(hoje.plusMonths(2));
        assertThat(vencimentos(nova.getId(), "PENDENTE")).containsExactly(dia, dia.plusMonths(1));
    }

    /**
     * Matrícula de antes da cobrança: ao subir, ganha só as que vencem de hoje em diante
     * (decisão do dono). Da segunda vez, não há nada a gerar.
     */
    private UUID matriculaAntiga(UUID plano, LocalDate inicio) {
        UUID matricula = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim,
                                        criada_em)
                VALUES (?, ?, ?, 'Antigo', 100, 6, ?, ?, now())""",
                matricula, aluno(), plano, inicio, inicio.plusMonths(6).minusDays(1));
        return matricula;
    }

    @Test
    void antigasGanhamSoAsDeHojeEmDiante() {
        UUID plano = plano(6, "MENSAL", null);
        LocalDate inicio = hoje.minusMonths(2).minusDays(5);
        List<UUID> matriculas = List.of(matriculaAntiga(plano, inicio), matriculaAntiga(plano, inicio));
        // Nos testes, a geração ao subir fica desligada (config de teste): aqui ela é chamada.
        // Lotes de 1: as duas matrículas caem em lotes diferentes.
        var antigas = new CobrancasDasMatriculasAntigas(repository, service, cobrancasDasMatriculas, 1);

        assertThat(antigas.gerar()).isGreaterThanOrEqualTo(2);

        for (UUID matricula : matriculas) {
            assertThat(vencimentos(matricula, "PENDENTE"))
                    .containsExactly(inicio.plusMonths(3), inicio.plusMonths(4), inicio.plusMonths(5));
            assertThat(jdbc.queryForObject("SELECT a_partir_de FROM cronogramas WHERE matricula_id = ?",
                    LocalDate.class, matricula)).isEqualTo(hoje);
        }
        assertThat(antigas.gerar()).isZero();
        assertThat(cobrancas(matriculas.getFirst())).hasSize(3);
    }
}
