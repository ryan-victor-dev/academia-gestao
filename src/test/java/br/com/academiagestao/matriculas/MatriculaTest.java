package br.com.academiagestao.matriculas;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatriculaTest {

    private static final UUID ALUNO = UUID.randomUUID();
    private static final UUID USUARIO = UUID.randomUUID();

    /** Data e hora no relógio de São Paulo. */
    private static Instant em(String dataHora) {
        return LocalDateTime.parse(dataHora).atZone(Matricula.FUSO).toInstant();
    }

    private static PlanoContratado plano(String valor, int meses) {
        return new PlanoContratado(UUID.randomUUID(), valor == null ? null : new BigDecimal(valor), meses);
    }

    private static Matricula mensal(String inicio, String agora) {
        return Matricula.nova(ALUNO, plano("129.90", 1), LocalDate.parse(inicio), em(agora));
    }

    @Test
    void copiaValorEDuracaoDoPlanoECalculaOPeriodo() {
        PlanoContratado trimestral = plano("119.9", 3);
        Instant agora = em("2026-10-17T10:00:00.123456789");

        Matricula matricula = Matricula.nova(ALUNO, trimestral, null, agora);

        assertThat(matricula.getId()).isNotNull();
        assertThat(matricula.getAlunoId()).isEqualTo(ALUNO);
        assertThat(matricula.getPlanoId()).isEqualTo(trimestral.planoId());
        assertThat(matricula.getValorMensal()).isEqualByComparingTo("119.90").hasToString("119.90");
        assertThat(matricula.getDuracaoMeses()).isEqualTo(3);
        assertThat(matricula.getValorTotal()).isEqualByComparingTo("359.70");
        // Sem início informado, começa hoje.
        assertThat(matricula.getInicio()).isEqualTo(LocalDate.parse("2026-10-17"));
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2027-01-16"));
        assertThat(matricula.getCriadaEm()).isEqualTo(em("2026-10-17T10:00:00.123456"));
        assertThat(matricula.getCanceladaEm()).isNull();
        assertThat(matricula.getCanceladaPor()).isNull();
        assertThat(matricula.getMotivoCancelamento()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-17, 1,  2026-11-16",
            "2026-10-17, 12, 2027-10-16",
            "2026-10-17, 60, 2031-10-16",
            // Sem o dia no mês final: cai no último dia dele, menos um.
            "2027-01-31, 1,  2027-02-27",
            "2028-01-31, 1,  2028-02-28",  // bissexto
            "2027-03-31, 1,  2027-04-29",
            "2026-12-01, 1,  2026-12-31",
    })
    void fimEhInicioMaisNMesesMenosUmDia(String inicio, int meses, String fimEsperado) {
        Matricula matricula = Matricula.nova(ALUNO, plano("100", meses), LocalDate.parse(inicio),
                em(inicio + "T08:00"));

        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse(fimEsperado));
    }

    @Test
    void renovacaoComecaNoDiaSeguinteAoFim() {
        Matricula atual = mensal("2027-01-31", "2027-01-31T08:00");
        Matricula renovacao = mensal(atual.getFim().plusDays(1).toString(), "2027-01-31T08:00");

        assertThat(renovacao.getInicio()).isEqualTo(LocalDate.parse("2027-02-28"));
    }

    @Test
    void inicioFuturoEhAceito() {
        assertThat(mensal("2026-11-01", "2026-10-17T10:00").getInicio()).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    @Test
    void inicioNoPassadoEhRecusado() {
        assertThatThrownBy(() -> mensal("2026-10-16", "2026-10-17T10:00"))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("passado");
    }

    @Test
    void hojeEhODiaEmSaoPauloENaoEmUtc() {
        // 23h30 de 17/10 em São Paulo já é 18/10 em UTC.
        Instant noiteDoDia17 = em("2026-10-17T23:30");

        assertThat(Matricula.nova(ALUNO, plano("100", 1), null, noiteDoDia17).getInicio())
                .isEqualTo(LocalDate.parse("2026-10-17"));
        assertThat(mensal("2026-10-17", "2026-10-17T23:30").situacao(noiteDoDia17))
                .isEqualTo(SituacaoMatricula.VIGENTE);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "0,         1,  maior que zero",
            "-10,       1,  maior que zero",
            "null,      1,  maior que zero",
            "129.999,   1,  2 casas",
            "100000000, 1,  no máximo",
            "100,       0,  1 a 60",
            "100,       61, 1 a 60",
    })
    void recusaPlanoInvalido(String valor, int meses, String mensagem) {
        assertThatThrownBy(() -> Matricula.nova(ALUNO, plano(valor, meses), null, em("2026-10-17T10:00")))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining(mensagem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "129.900", "99999999.99"})
    void aceitaValoresValidos(String valor) {
        assertThat(Matricula.nova(ALUNO, plano(valor, 1), null, em("2026-10-17T10:00")).getValorMensal().scale())
                .isEqualTo(2);
    }

    @Test
    void duracoesNosLimitesSaoAceitas() {
        assertThat(Matricula.nova(ALUNO, plano("100", 1), null, em("2026-10-17T10:00")).getDuracaoMeses()).isEqualTo(1);
        assertThat(Matricula.nova(ALUNO, plano("100", 60), null, em("2026-10-17T10:00")).getDuracaoMeses()).isEqualTo(60);
    }

    @Test
    void alunoEPlanoSaoObrigatorios() {
        Instant agora = em("2026-10-17T10:00");
        assertThatThrownBy(() -> Matricula.nova(null, plano("100", 1), null, agora))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("aluno");
        assertThatThrownBy(() -> Matricula.nova(ALUNO, null, null, agora))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("plano");
        assertThatThrownBy(() -> Matricula.nova(ALUNO, new PlanoContratado(null, BigDecimal.TEN, 1), null, agora))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("plano");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-31T23:59, AGENDADA",   // véspera do início
            "2026-11-01T00:00, VIGENTE",    // primeiro dia
            "2026-11-30T23:59, VIGENTE",    // último dia (fim é inclusivo)
            "2026-12-01T00:00, ENCERRADA",  // dia seguinte ao fim
    })
    void situacaoSaiDasDatas(String agora, SituacaoMatricula esperada) {
        Matricula matricula = mensal("2026-11-01", "2026-10-17T10:00");

        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(matricula.situacao(em(agora))).isEqualTo(esperada);
    }

    @Test
    void cancelarVigenteGuardaQuemQuandoEPorQue() {
        Matricula matricula = mensal("2026-10-17", "2026-10-17T10:00");
        Instant agora = em("2026-10-20T15:00:00.000000999");

        matricula.cancelar(USUARIO, "  Mudou de cidade  ", agora);

        assertThat(matricula.situacao(agora)).isEqualTo(SituacaoMatricula.CANCELADA);
        assertThat(matricula.getCanceladaEm()).isEqualTo(em("2026-10-20T15:00"));
        assertThat(matricula.getCanceladaPor()).isEqualTo(USUARIO);
        assertThat(matricula.getMotivoCancelamento()).isEqualTo("Mudou de cidade");
        // O período contratado não muda: continua registrado o que foi vendido.
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2026-11-16"));
    }

    @Test
    void cancelarAgendadaAntesDeComecar() {
        Matricula matricula = mensal("2026-11-01", "2026-10-17T10:00");

        matricula.cancelar(USUARIO, "Desistiu", em("2026-10-20T15:00"));

        // Cancelada vale para sempre, mesmo depois das datas do período.
        assertThat(matricula.situacao(em("2026-11-10T10:00"))).isEqualTo(SituacaoMatricula.CANCELADA);
        assertThat(matricula.situacao(em("2027-01-01T10:00"))).isEqualTo(SituacaoMatricula.CANCELADA);
    }

    @Test
    void naoCancelaEncerrada() {
        Matricula matricula = mensal("2026-10-17", "2026-10-17T10:00");

        assertThatThrownBy(() -> matricula.cancelar(USUARIO, "Tarde demais", em("2026-11-17T10:00")))
                .isInstanceOf(MatriculaNaoCancelavelException.class)
                .hasMessageContaining("encerrada");
        assertThat(matricula.getCanceladaEm()).isNull();
    }

    @Test
    void naoCancelaDuasVezes() {
        Matricula matricula = mensal("2026-10-17", "2026-10-17T10:00");
        matricula.cancelar(USUARIO, "Primeiro motivo", em("2026-10-18T10:00"));

        assertThatThrownBy(() -> matricula.cancelar(UUID.randomUUID(), "Segundo", em("2026-10-19T10:00")))
                .isInstanceOf(MatriculaNaoCancelavelException.class)
                .hasMessageContaining("cancelada");
        assertThat(matricula.getMotivoCancelamento()).isEqualTo("Primeiro motivo");
        assertThat(matricula.getCanceladaPor()).isEqualTo(USUARIO);
        assertThat(matricula.getCanceladaEm()).isEqualTo(em("2026-10-18T10:00"));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "null, obrigatório",
            "'',   obrigatório",
            "'   ', obrigatório",
    })
    void motivoEhObrigatorio(String motivo, String mensagem) {
        Matricula matricula = mensal("2026-10-17", "2026-10-17T10:00");

        assertThatThrownBy(() -> matricula.cancelar(USUARIO, motivo, em("2026-10-18T10:00")))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining(mensagem);
        assertThat(matricula.situacao(em("2026-10-18T10:00"))).isEqualTo(SituacaoMatricula.VIGENTE);
    }

    @Test
    void motivoTemNoMaximo500Caracteres() {
        Matricula matricula = mensal("2026-10-17", "2026-10-17T10:00");
        Instant agora = em("2026-10-18T10:00");

        assertThatThrownBy(() -> matricula.cancelar(USUARIO, "x".repeat(501), agora))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("500");
        assertThat(matricula.getCanceladaEm()).isNull();

        matricula.cancelar(USUARIO, "x".repeat(500), agora);
        assertThat(matricula.getMotivoCancelamento()).hasSize(500);
    }

    @Test
    void quemCancelouEhObrigatorioENadaMuda() {
        Matricula matricula = mensal("2026-10-17", "2026-10-17T10:00");

        assertThatThrownBy(() -> matricula.cancelar(null, "Motivo", em("2026-10-18T10:00")))
                .isInstanceOf(DadosInvalidosException.class);
        assertThat(matricula.getCanceladaEm()).isNull();
        assertThat(matricula.getMotivoCancelamento()).isNull();
    }
}
