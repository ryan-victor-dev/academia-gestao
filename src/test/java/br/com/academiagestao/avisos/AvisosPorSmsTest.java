package br.com.academiagestao.avisos;

import br.com.academiagestao.CorreioDeTeste;
import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os avisos de fatura também por SMS (ADR 0047, decisões 7 e 9), com o SMS simulado: cada canal tem a sua reserva (um
 * não impede nem repete o outro), só celular recebe, e o e-mail fora do ar não segura o SMS.
 */
@Import({TestcontainersConfiguration.class, CorreioDeTeste.Config.class})
@SpringBootTest(properties = {"academia.avisos.primeira-vez=PT1H", "academia.sms.provedor=simulado"})
class AvisosPorSmsTest {

    @Autowired
    AvisosDeFatura avisos;

    @Autowired
    CorreioDeTeste correio;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    br.com.academiagestao.RelogioDeTeste relogio;

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
        correio.desligar(false);
        correio.falhar(false);
        for (UUID aluno : alunos) {
            jdbc.update("DELETE FROM avisos_de_fatura WHERE cobranca_id IN (SELECT id FROM cobrancas WHERE aluno_id = ?)", aluno);
            limpeza.apagar("alunos", aluno);
        }
        limpeza.apagar("planos", plano);
    }

    private UUID faturaVencendoHoje(String email, String telefone) {
        UUID aluno = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email, telefone) VALUES (?, 'Caio Reis', ?, ?, ?)", aluno,
                CpfsParaTeste.aleatorio(), email, telefone);
        alunos.add(aluno);
        UUID matricula = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'P', 120, 12, ?, ?, now())""", matricula, aluno, plano, hoje.minusMonths(1),
                hoje.minusMonths(1).plusMonths(12).minusDays(1));
        UUID fatura = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, ?, 'MENSALIDADE', 1, 120, ?, 'PENDENTE')""", fatura, matricula, aluno, hoje);
        return fatura;
    }

    private List<String> smsPara(String celular) {
        return jdbc.queryForList("SELECT texto FROM sms_simulados WHERE celular = ? ORDER BY enviado_em", String.class, celular);
    }

    private static String celularAleatorio() {
        return "119" + String.format("%08d", (long) (Math.random() * 100_000_000));
    }

    @Test
    void emailESmsCadaUmUmaVez() {
        String celular = celularAleatorio();
        String email = "sms-" + UUID.randomUUID() + "@teste.local";
        UUID fatura = faturaVencendoHoje(email, celular);
        try {
            avisos.enviarOsDeHoje(hoje);
            avisos.enviarOsDeHoje(hoje);

            assertThat(correio.para(email)).hasSize(1);
            assertThat(smsPara(celular)).singleElement().asString()
                    .startsWith("Academia: oi, Caio! Sua mensalidade 1 de R$ 120,00 vence hoje.");
            assertThat(jdbc.queryForList("SELECT canal || '/' || situacao FROM avisos_de_fatura WHERE cobranca_id = ? ORDER BY canal",
                    String.class, fatura)).containsExactly("EMAIL/ENVIADO", "SMS/ENVIADO");
        } finally {
            jdbc.update("DELETE FROM sms_simulados WHERE celular = ?", celular);
        }
    }

    @Test
    void telefoneFixoNaoRecebeSms() {
        String email = "sms-" + UUID.randomUUID() + "@teste.local";
        UUID fatura = faturaVencendoHoje(email, "1133334444");

        avisos.enviarOsDeHoje(hoje);

        assertThat(correio.para(email)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT canal FROM avisos_de_fatura WHERE cobranca_id = ?", String.class, fatura))
                .containsExactly("EMAIL");
        assertThat(smsPara("1133334444")).isEmpty();
    }

    @Test
    void semEmailOSmsSaiEOEmailForaDoArNaoSeguraOSms() {
        String celular = celularAleatorio();
        String outroCelular = celularAleatorio();
        String email = "sms-" + UUID.randomUUID() + "@teste.local";
        faturaVencendoHoje(null, celular);
        UUID comEmail = faturaVencendoHoje(email, outroCelular);
        correio.falhar(true);
        try {
            avisos.enviarOsDeHoje(hoje);

            assertThat(smsPara(celular)).hasSize(1);
            assertThat(smsPara(outroCelular)).hasSize(1);
            assertThat(jdbc.queryForList("SELECT canal || '/' || situacao FROM avisos_de_fatura WHERE cobranca_id = ? ORDER BY canal",
                    String.class, comEmail)).containsExactly("EMAIL/FALHOU", "SMS/ENVIADO");
        } finally {
            jdbc.update("DELETE FROM sms_simulados WHERE celular IN (?, ?)", celular, outroCelular);
        }
    }

    /** Sem servidor de e-mail, os avisos rodam do mesmo jeito, só por SMS. */
    @Test
    void semEmailConfiguradoOsAvisosSaemSoPorSms() {
        String celular = celularAleatorio();
        String email = "sms-" + UUID.randomUUID() + "@teste.local";
        UUID fatura = faturaVencendoHoje(email, celular);
        correio.desligar(true);
        relogio.fixar(hoje.atTime(9, 30).atZone(Calendario.FUSO).toInstant());
        try {
            avisos.executar();

            assertThat(smsPara(celular)).hasSize(1);
            assertThat(correio.para(email)).isEmpty();
            assertThat(jdbc.queryForList("SELECT canal FROM avisos_de_fatura WHERE cobranca_id = ?", String.class, fatura))
                    .containsExactly("SMS");
        } finally {
            jdbc.update("DELETE FROM sms_simulados WHERE celular = ?", celular);
        }
    }
}
