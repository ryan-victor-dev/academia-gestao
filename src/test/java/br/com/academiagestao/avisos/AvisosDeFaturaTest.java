package br.com.academiagestao.avisos;

import br.com.academiagestao.CorreioDeTeste;
import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.compartilhado.Correio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os avisos de fatura por e-mail (ADR 0047, decisão 7): cada marco uma vez, nunca a quem pagou, ao inativo só os de
 * atraso, só a quem tem e-mail, de novo se o servidor de e-mail falhou (até 3 vezes), e só a partir das 9h.
 */
@Import({TestcontainersConfiguration.class, CorreioDeTeste.Config.class})
@SpringBootTest(properties = "academia.avisos.primeira-vez=PT1H")
class AvisosDeFaturaTest {

    @Autowired
    AvisosDeFatura avisos;

    @Autowired
    CorreioDeTeste correio;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    DataSource dataSource;

    private final List<UUID> alunos = new ArrayList<>();
    private UUID plano;
    private LocalDate hoje;

    @BeforeEach
    void preparar() {
        hoje = Calendario.hoje(Instant.now());
        plano = UUID.randomUUID();
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 120, 12)", plano, "P " + plano);
        correio.falhar(false);
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        correio.falhar(false);
        for (UUID aluno : alunos) {
            jdbc.update("DELETE FROM avisos_de_fatura WHERE cobranca_id IN (SELECT id FROM cobrancas WHERE aluno_id = ?)", aluno);
            jdbc.update("DELETE FROM auditoria WHERE entidade_id IN (SELECT id FROM cobrancas WHERE aluno_id = ?)", aluno);
            limpeza.apagar("alunos", aluno);
        }
        limpeza.apagar("planos", plano);
    }

    /** Um aluno com uma matrícula (as cobranças precisam dela). @return o id da matrícula */
    private UUID aluno(String email, boolean ativo) {
        UUID aluno = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email, ativo) VALUES (?, 'Bia Lima', ?, ?, ?)", aluno,
                CpfsParaTeste.aleatorio(), email, ativo);
        alunos.add(aluno);
        UUID matricula = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'P', 120, 12, ?, ?, now())""", matricula, aluno, plano, hoje.minusMonths(1), hoje.minusMonths(1).plusMonths(12).minusDays(1));
        return matricula;
    }

    /** Uma mensalidade em aberto vencendo daqui a {@code dias} (negativo: já venceu). */
    private UUID cobranca(UUID matricula, int parcela, int dias) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, (SELECT aluno_id FROM matriculas WHERE id = ?), 'MENSALIDADE', ?, 120, ?, 'PENDENTE')""",
                id, matricula, matricula, parcela, hoje.plusDays(dias));
        return id;
    }

    private void cancelar(UUID cobranca) {
        jdbc.update("UPDATE cobrancas SET status = 'CANCELADA', cancelada_em = now(), motivo_cancelamento = 'teste' WHERE id = ?", cobranca);
    }

    private List<String> assuntos(String email) {
        return correio.para(email).stream().map(Correio.Mensagem::assunto).toList();
    }

    @Test
    void cadaMarcoSaiUmaVezSo() {
        String email = "aviso-" + UUID.randomUUID() + "@teste.local";
        UUID matricula = aluno(email, true);
        int parcela = 1;
        for (int dias : new int[] {5, 3, 1, 0, -1, -3, -5}) {
            cobranca(matricula, parcela++, dias);
        }

        avisos.enviarOsDeHoje(hoje);

        assertThat(assuntos(email)).containsExactlyInAnyOrder(
                "Lembrete amigo: sua mensalidade 1 vence em 5 dias", "Lembrete amigo: sua mensalidade 2 vence em 3 dias",
                "Lembrete amigo: sua mensalidade 3 vence em 1 dia", "Hoje é o dia: pague sua mensalidade 4 sem acréscimo",
                "Sua mensalidade 5 está atrasada há 1 dia", "Sua mensalidade 6 está atrasada há 3 dias",
                "Sua mensalidade 7 está atrasada há 5 dias");
        // O atrasado traz o valor de hoje, com acréscimo e juros (as regras padrão: 2% e 1% ao mês).
        assertThat(correio.para(email).stream().filter(m -> m.assunto().contains("há 5 dias")).findFirst().orElseThrow().texto())
                .contains("O valor para pagar hoje é R$ 122,").contains("bloqueia o acesso à academia");

        avisos.enviarOsDeHoje(hoje);
        assertThat(correio.para(email)).as("a segunda rodada não repete").hasSize(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM avisos_de_fatura a JOIN cobrancas c ON c.id = a.cobranca_id "
                + "WHERE c.matricula_id = ? AND a.situacao = 'ENVIADO' AND a.canal = 'EMAIL'", Integer.class, matricula)).isEqualTo(7);
    }

    @Test
    void quemPagouOuCancelouEOsDiasForaDosMarcosNaoRecebem() {
        String email = "aviso-" + UUID.randomUUID() + "@teste.local";
        UUID matricula = aluno(email, true);
        UUID paga = cobranca(matricula, 1, -3);
        UUID recepcao = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'R', ?, 'x', 'RECEPCAO')",
                recepcao, "aviso-" + recepcao + "@teste.local");
        jdbc.update("""
                UPDATE cobrancas SET status = 'PAGA', paga_em = now(), paga_por = ?, pago_total = 120, pago_acrescimo = 0,
                    pago_juros = 0, meio_de_pagamento = 'DINHEIRO' WHERE id = ?""", recepcao, paga);
        cancelar(cobranca(matricula, 2, 5));
        for (int dias : new int[] {2, 4, -2, -4, -6, 10}) {
            cobranca(matricula, 10 + dias, dias);
        }

        try {
            avisos.enviarOsDeHoje(hoje);
            assertThat(correio.para(email)).isEmpty();
        } finally {
            jdbc.update("UPDATE cobrancas SET status = 'CANCELADA', cancelada_em = now(), motivo_cancelamento = 'teste', paga_em = NULL,"
                    + " paga_por = NULL, pago_total = NULL, pago_acrescimo = NULL, pago_juros = NULL, meio_de_pagamento = NULL WHERE id = ?", paga);
            limpeza.apagar("usuarios", recepcao);
        }
    }

    /** Quem saiu da academia recebe só os de atraso, da fatura que ficou em aberto; sem e-mail, nada. */
    @Test
    void inativoSoRecebeOsDeAtrasoESemEmailNada() {
        String inativo = "aviso-" + UUID.randomUUID() + "@teste.local";
        UUID matricula = aluno(inativo, false);
        int parcela = 1;
        for (int dias : new int[] {5, 3, 1, 0, -1, -3, -5}) {
            cobranca(matricula, parcela++, dias);
        }
        UUID semEmail = aluno(null, true);
        UUID daSemEmail = cobranca(semEmail, 1, -1);

        avisos.enviarOsDeHoje(hoje);

        assertThat(assuntos(inativo)).containsExactlyInAnyOrder("Sua mensalidade 5 está atrasada há 1 dia",
                "Sua mensalidade 6 está atrasada há 3 dias", "Sua mensalidade 7 está atrasada há 5 dias");
        // Sem o bloqueio e sem o app: o login do inativo só consulta, e ele paga na recepção.
        assertThat(correio.para(inativo)).allSatisfy(m -> assertThat(m.texto()).doesNotContain("bloque")
                .contains("Você pode pagar na recepção da academia."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM avisos_de_fatura WHERE cobranca_id = ?", Integer.class, daSemEmail)).isZero();
    }

    @Test
    void servidorDeEmailForaDoArTentaDeNovoAteTresVezes() {
        String email = "aviso-" + UUID.randomUUID() + "@teste.local";
        UUID fatura = cobranca(aluno(email, true), 1, 0);
        correio.falhar(true);

        avisos.enviarOsDeHoje(hoje);
        avisos.enviarOsDeHoje(hoje);
        assertThat(jdbc.queryForObject("SELECT situacao || '/' || tentativas FROM avisos_de_fatura WHERE cobranca_id = ?",
                String.class, fatura)).isEqualTo("FALHOU/2");
        correio.falhar(false);
        avisos.enviarOsDeHoje(hoje);
        assertThat(correio.para(email)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT situacao || '/' || tentativas FROM avisos_de_fatura WHERE cobranca_id = ?",
                String.class, fatura)).isEqualTo("ENVIADO/3");

        // Três falhas: desiste no dia.
        String outro = "aviso-" + UUID.randomUUID() + "@teste.local";
        UUID outraFatura = cobranca(aluno(outro, true), 1, 0);
        correio.falhar(true);
        for (int i = 0; i < 4; i++) {
            avisos.enviarOsDeHoje(hoje);
        }
        correio.falhar(false);
        avisos.enviarOsDeHoje(hoje);
        assertThat(correio.para(outro)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT situacao || '/' || tentativas FROM avisos_de_fatura WHERE cobranca_id = ?",
                String.class, outraFatura)).isEqualTo("FALHOU/3");
    }

    @Test
    void soAPartirDasNoveDaManha() {
        String email = "aviso-" + UUID.randomUUID() + "@teste.local";
        cobranca(aluno(email, true), 1, 0);

        relogio.fixar(hoje.atTime(8, 59).atZone(Calendario.FUSO).toInstant());
        avisos.executar();
        assertThat(correio.para(email)).isEmpty();

        relogio.fixar(hoje.atTime(9, 0).atZone(Calendario.FUSO).toInstant());
        avisos.executar();
        assertThat(correio.para(email)).hasSize(1);
    }

    /**
     * Corrida com o pagamento (aqui, um cancelamento, que também tira a fatura de "em aberto"): outra transação muda a
     * fatura e segura a linha; o aviso, que já a viu em aberto, espera na trava (conferido no pg_stat_activity) e, com a
     * mudança confirmada, não sai.
     */
    @Test
    void faturaQueDeixouDeEstarEmAbertoNoMeioNaoRecebe() throws Exception {
        String email = "aviso-" + UUID.randomUUID() + "@teste.local";
        UUID fatura = cobranca(aluno(email, true), 1, -1);
        try (Connection outra = dataSource.getConnection()) {
            outra.setAutoCommit(false);
            try (PreparedStatement mudar = outra.prepareStatement(
                    "UPDATE cobrancas SET status = 'CANCELADA', cancelada_em = now(), motivo_cancelamento = 'pagou' WHERE id = ?")) {
                mudar.setObject(1, fatura);
                mudar.executeUpdate();
            }
            CompletableFuture<Integer> rodada = CompletableFuture.supplyAsync(() -> avisos.enviarOsDeHoje(hoje));
            Instant limite = Instant.now().plus(Duration.ofSeconds(5));
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%FOR SHARE%'",
                    Integer.class) == 0) {
                assertThat(Instant.now()).as("o aviso não chegou a esperar na trava").isBefore(limite);
                Thread.sleep(20);
            }
            outra.commit();
            rodada.get(10, TimeUnit.SECONDS);
        }
        assertThat(correio.para(email)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM avisos_de_fatura WHERE cobranca_id = ?", Integer.class, fatura)).isZero();
    }
}
