package br.com.academiagestao.matriculas;

import br.com.academiagestao.cobranca.dominio.MatriculaParaCobranca;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import br.com.academiagestao.planos.FormaPagamento;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatriculaTest {

    private static final UUID ALUNO = UUID.randomUUID();
    private static final UUID USUARIO = UUID.randomUUID();

    /** Data e hora no relógio de São Paulo. */
    private static Instant em(String dataHora) {
        return LocalDateTime.parse(dataHora).atZone(Matricula.FUSO).toInstant();
    }

    private static PlanoContratado plano(String valor, int meses) {
        return new PlanoContratado(UUID.randomUUID(), "Mensal", valor == null ? null : new BigDecimal(valor), meses);
    }

    private static Matricula mensal(String inicio, String agora) {
        return Matricula.nova(ALUNO, plano("129.90", 1), LocalDate.parse(inicio), em(agora));
    }

    @Test
    void copiaValorEDuracaoDoPlanoECalculaOPeriodo() {
        PlanoContratado trimestral = new PlanoContratado(UUID.randomUUID(), "  Plano Trimestral ",
                new BigDecimal("119.9"), 3);
        Instant agora = em("2026-10-17T10:00:00.123456789");

        Matricula matricula = Matricula.nova(ALUNO, trimestral, null, agora);

        assertThat(matricula.getId()).isNotNull();
        assertThat(matricula.getAlunoId()).isEqualTo(ALUNO);
        assertThat(matricula.getPlanoId()).isEqualTo(trimestral.planoId());
        assertThat(matricula.getPlanoNome()).isEqualTo("Plano Trimestral");
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
    void inicioPodeSerAteUmAnoAFrente() {
        assertThat(mensal("2027-10-17", "2026-10-17T10:00").getInicio()).isEqualTo(LocalDate.parse("2027-10-17"));
        assertThatThrownBy(() -> mensal("2027-10-18", "2026-10-17T10:00"))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("1 ano à frente");
    }

    // Revisão de 2026-10-17: o ano máximo do Java estourava o cálculo do fim (DateTimeException, 500).
    @Test
    void anoGiganteEhRecusadoSemEstourarOCalculoDoFim() {
        assertThatThrownBy(() -> mensal(LocalDate.MAX.toString(), "2026-10-17T10:00"))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("1 ano à frente");
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
            "100000.01, 1,  no máximo",
            "100,       0,  1 a 60",
            "100,       61, 1 a 60",
    })
    void recusaPlanoInvalido(String valor, int meses, String mensagem) {
        assertThatThrownBy(() -> Matricula.nova(ALUNO, plano(valor, meses), null, em("2026-10-17T10:00")))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining(mensagem);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null", "''", "'   '"})
    void nomeDoPlanoEhObrigatorio(String nome) {
        PlanoContratado semNome = new PlanoContratado(UUID.randomUUID(), nome, BigDecimal.TEN, 1);

        assertThatThrownBy(() -> Matricula.nova(ALUNO, semNome, null, em("2026-10-17T10:00")))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("nome do plano");
    }

    @Test
    void nomeDoPlanoTemNoMaximo80Caracteres() {
        Instant agora = em("2026-10-17T10:00");
        PlanoContratado longo = new PlanoContratado(UUID.randomUUID(), "x".repeat(81), BigDecimal.TEN, 1);
        PlanoContratado limite = new PlanoContratado(UUID.randomUUID(), "x".repeat(80), BigDecimal.TEN, 1);

        assertThatThrownBy(() -> Matricula.nova(ALUNO, longo, null, agora))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("80");
        assertThat(Matricula.nova(ALUNO, limite, null, agora).getPlanoNome()).hasSize(80);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "129.900", "100000.00"})
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
        assertThatThrownBy(() -> Matricula.nova(ALUNO, new PlanoContratado(null, "Mensal", BigDecimal.TEN, 1), null, agora))
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

    // Fidelidade, forma de pagamento e multa (ADR 0018)

    private static PlanoContratado anualComFidelidade(String percentual) {
        return new PlanoContratado(UUID.randomUUID(), "Anual", new BigDecimal("99.90"), 12, true,
                new BigDecimal(percentual), FormaPagamento.A_VISTA);
    }

    @Test
    void copiaAsRegrasDoPlano() {
        Matricula matricula = Matricula.nova(ALUNO, anualComFidelidade("12.5"), null, em("2026-10-17T10:00"));

        assertThat(matricula.isFidelidade()).isTrue();
        assertThat(matricula.getPercentualMulta()).hasToString("12.50");
        assertThat(matricula.getFormaPagamento()).isEqualTo(FormaPagamento.A_VISTA);
        assertThat(matricula.getMulta()).isNull();
        Matricula semFidelidade = mensal("2026-10-17", "2026-10-17T10:00");
        assertThat(semFidelidade.isFidelidade()).isFalse();
        assertThat(semFidelidade.getPercentualMulta()).isNull();
        assertThat(semFidelidade.getFormaPagamento()).isEqualTo(FormaPagamento.MENSAL);
    }

    @ParameterizedTest(name = "cancelada em {0}: {1} meses restantes, multa {2}")
    @CsvSource({
            "2026-10-17, 11, 219.78",  // primeiro dia: o 1º mês já está em curso
            "2027-01-17,  8, 159.84",  // dia em que um mês começa: ele já está em curso
            "2027-01-20,  8, 159.84",  // exemplo do ADR 0018
            "2027-02-16,  8, 159.84",  // último dia do mês em curso
            "2027-02-17,  7, 139.86",
            "2027-10-16,  0, 0.00",    // último dia do período
    })
    void multaEhOPercentualDosMesesInteirosQueFaltam(String dia, int meses, String multa) {
        Matricula matricula = Matricula.nova(ALUNO, anualComFidelidade("20"), null, em("2026-10-17T10:00"));
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2027-10-16"));

        matricula.cancelar(USUARIO, "Mudou de cidade", em(dia + "T15:00"));

        assertThat(matricula.getMulta()).hasToString(multa);
        assertThat(new BigDecimal("99.90").multiply(BigDecimal.valueOf(meses)).multiply(new BigDecimal("0.20"))
                .setScale(2, java.math.RoundingMode.HALF_UP)).hasToString(multa);
    }

    @Test
    void multaArredondaOsCentavosParaCima() {
        Matricula matricula = Matricula.nova(ALUNO, anualComFidelidade("12.5"), null, em("2026-10-17T10:00"));

        // 1 mês restante: 99,90 × 12,5% = 12,4875, que vira 12,49.
        matricula.cancelar(USUARIO, "Motivo", em("2027-08-20T10:00"));

        assertThat(matricula.getMulta()).hasToString("12.49");
    }

    @Test
    void semFidelidadeNaoTemMulta() {
        Matricula matricula = mensal("2026-10-17", "2026-10-17T10:00");

        matricula.cancelar(USUARIO, "Motivo", em("2026-10-20T10:00"));

        assertThat(matricula.getMulta()).isNull();
    }

    @Test
    void agendadaComFidelidadeCanceladaNaoTemMulta() {
        Matricula matricula = Matricula.nova(ALUNO, anualComFidelidade("20"), LocalDate.parse("2026-11-01"),
                em("2026-10-17T10:00"));

        matricula.cancelar(USUARIO, "Desistiu antes de começar", em("2026-10-20T10:00"));

        assertThat(matricula.getMulta()).isNull();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "true,  null,   0,01 a 100",
            "true,  0,      0,01 a 100",
            "true,  100.01, 0,01 a 100",
            "true,  12.345, 0,01 a 100",
            "false, 20,     só existe em plano com fidelidade",
    })
    void recusaRegrasDeFidelidadeIncoerentes(boolean fidelidade, String percentual, String mensagem) {
        PlanoContratado plano = new PlanoContratado(UUID.randomUUID(), "Anual", new BigDecimal("99.90"), 12,
                fidelidade, percentual == null ? null : new BigDecimal(percentual), FormaPagamento.MENSAL);

        assertThatThrownBy(() -> Matricula.nova(ALUNO, plano, null, em("2026-10-17T10:00")))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining(mensagem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "100"})
    void percentuaisNosLimitesSaoAceitos(String percentual) {
        assertThat(Matricula.nova(ALUNO, anualComFidelidade(percentual), null, em("2026-10-17T10:00"))
                .getPercentualMulta().scale()).isEqualTo(2);
    }

    @Test
    void formaDePagamentoEhObrigatoria() {
        PlanoContratado semForma = new PlanoContratado(UUID.randomUUID(), "Anual", new BigDecimal("99.90"), 12,
                false, null, null);

        assertThatThrownBy(() -> Matricula.nova(ALUNO, semForma, null, em("2026-10-17T10:00")))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("forma de pagamento");
    }

    // Trancamento (ADR 0018)

    /** Mensal de 17/10 a 16/11/2026, vigente. */
    private static Matricula vigente() {
        return mensal("2026-10-17", "2026-10-17T10:00");
    }

    private static void trancar(Matricula m, String inicio, String volta, String agora) {
        m.trancar(inicio == null ? null : LocalDate.parse(inicio), LocalDate.parse(volta), "Viagem", USUARIO,
                em(agora));
    }

    @Test
    void trancarEstendeOFimPelosDiasParados() {
        Matricula matricula = vigente();

        trancar(matricula, null, "2026-11-19", "2026-10-20T10:00");  // de 20/10 a 18/11: 30 dias

        assertThat(matricula.getDiasTrancados()).isEqualTo(30);
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2026-12-16"));
        Trancamento t = matricula.getTrancamentos().getFirst();
        assertThat(t.getInicio()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(t.getVoltaPrevista()).isEqualTo(LocalDate.parse("2026-11-19"));
        assertThat(t.getMotivo()).isEqualTo("Viagem");
        assertThat(t.getCriadoPor()).isEqualTo(USUARIO);
        assertThat(t.dias()).isEqualTo(30);
        assertThat(t.getId()).isNotNull();
        assertThat(t.getCriadoEm()).isEqualTo(em("2026-10-20T10:00"));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-24T23:59, VIGENTE",   // véspera do trancamento agendado
            "2026-10-25T00:00, TRANCADA",  // primeiro dia parado
            "2026-11-23T23:59, TRANCADA",  // véspera da volta
            "2026-11-24T00:00, VIGENTE",   // dia da volta
            "2026-12-16T12:00, VIGENTE",   // último dia do fim estendido
            "2026-12-17T00:00, ENCERRADA",
    })
    void situacaoDuranteOTrancamento(String agora, SituacaoMatricula esperada) {
        Matricula matricula = vigente();
        trancar(matricula, "2026-10-25", "2026-11-24", "2026-10-20T10:00");  // 30 dias, agendado

        assertThat(matricula.situacao(em(agora))).isEqualTo(esperada);
    }

    @ParameterizedTest
    @CsvSource({"6, false", "7, true", "90, true", "91, false"})
    void trancamentoDe7a90Dias(int dias, boolean aceito) {
        Matricula matricula = vigente();
        String volta = LocalDate.parse("2026-10-20").plusDays(dias).toString();

        if (aceito) {
            trancar(matricula, "2026-10-20", volta, "2026-10-20T10:00");
            assertThat(matricula.getDiasTrancados()).isEqualTo(dias);
        } else {
            assertThatThrownBy(() -> trancar(matricula, "2026-10-20", volta, "2026-10-20T10:00"))
                    .isInstanceOf(DadosInvalidosException.class)
                    .hasMessageContaining("7 a 90 dias");
            assertThat(matricula.getDiasTrancados()).isZero();
            assertThat(matricula.getTrancamentos()).isEmpty();
        }
    }

    @Test
    void noMaximo90DiasSomadosNaMatricula() {
        Matricula matricula = Matricula.nova(ALUNO, anualComFidelidade("20"), null, em("2026-10-17T10:00"));
        trancar(matricula, "2026-10-20", "2026-12-19", "2026-10-20T10:00");  // 60 dias
        LocalDate fimDepoisDoPrimeiro = matricula.getFim();

        // Em 20/12 ele já voltou; pede mais 31: passaria de 90.
        assertThatThrownBy(() -> trancar(matricula, "2027-01-05", "2027-02-05", "2026-12-20T10:00"))
                .isInstanceOf(MatriculaRecusadaException.class)
                .hasMessageContaining("60 dias trancados")
                .hasMessageContaining("restam 30");
        assertThat(matricula.getFim()).isEqualTo(fimDepoisDoPrimeiro);

        trancar(matricula, "2027-01-05", "2027-02-04", "2026-12-20T10:00");  // exatamente 30
        assertThat(matricula.getDiasTrancados()).isEqualTo(90);
    }

    @Test
    void umTrancamentoPorVez() {
        Matricula matricula = vigente();
        trancar(matricula, "2026-10-25", "2026-11-01", "2026-10-20T10:00");  // agendado

        assertThatThrownBy(() -> trancar(matricula, "2026-11-05", "2026-11-12", "2026-10-21T10:00"))
                .isInstanceOf(MatriculaRecusadaException.class)
                .hasMessageContaining("já existe um trancamento".substring(1));
        // Durante o trancamento, a matrícula não está vigente.
        assertThatThrownBy(() -> trancar(matricula, null, "2026-11-12", "2026-10-26T10:00"))
                .isInstanceOf(MatriculaRecusadaException.class)
                .hasMessageContaining("trancada");
    }

    @Test
    void soSeTrancaMatriculaVigente() {
        Matricula agendada = mensal("2026-11-01", "2026-10-17T10:00");
        assertThatThrownBy(() -> trancar(agendada, null, "2026-10-30", "2026-10-20T10:00"))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("agendada");

        Matricula encerrada = vigente();
        assertThatThrownBy(() -> trancar(encerrada, null, "2026-12-01", "2026-11-20T10:00"))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("encerrada");

        Matricula cancelada = vigente();
        cancelada.cancelar(USUARIO, "Motivo", em("2026-10-18T10:00"));
        assertThatThrownBy(() -> trancar(cancelada, null, "2026-10-30", "2026-10-20T10:00"))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("cancelada");
    }

    @Test
    void datasEMotivoDoTrancamento() {
        Matricula matricula = vigente();
        Instant agora = em("2026-10-20T10:00");

        assertThatThrownBy(() -> trancar(matricula, "2026-10-19", "2026-11-19", "2026-10-20T10:00"))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("entre hoje e o fim");
        assertThatThrownBy(() -> trancar(matricula, "2026-11-17", "2026-11-30", "2026-10-20T10:00"))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("entre hoje e o fim");
        assertThatThrownBy(() -> matricula.trancar(null, null, "Viagem", USUARIO, agora))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("volta prevista");
        assertThatThrownBy(() -> matricula.trancar(null, LocalDate.parse("2026-11-19"), "  ", USUARIO, agora))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("motivo");
        assertThatThrownBy(() -> matricula.trancar(null, LocalDate.parse("2026-11-19"), "Viagem", null, agora))
                .isInstanceOf(DadosInvalidosException.class);
        assertThat(matricula.getTrancamentos()).isEmpty();

        // O último dia do período ainda pode começar um trancamento.
        trancar(matricula, "2026-11-16", "2026-11-23", "2026-10-20T10:00");
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2026-11-23"));
    }

    @Test
    void voltaAntesDoPrevistoContaSoOsDiasParados() {
        Matricula matricula = vigente();
        trancar(matricula, "2026-10-20", "2026-11-19", "2026-10-20T10:00");  // 30 dias

        matricula.registrarVolta(USUARIO, em("2026-10-30T09:00"));  // voltou no 11º dia: 10 parados

        assertThat(matricula.getDiasTrancados()).isEqualTo(10);
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2026-11-26"));
        assertThat(matricula.situacao(em("2026-10-30T09:00"))).isEqualTo(SituacaoMatricula.VIGENTE);
        Trancamento t = matricula.getTrancamentos().getFirst();
        assertThat(t.getVolta()).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(t.getVoltaRegistradaPor()).isEqualTo(USUARIO);
        assertThat(t.dias()).isEqualTo(10);
    }

    @Test
    void voltaAntesDeComecarDesfazOTrancamento() {
        Matricula matricula = vigente();
        trancar(matricula, "2026-11-01", "2026-11-15", "2026-10-20T10:00");

        matricula.registrarVolta(USUARIO, em("2026-10-25T10:00"));

        assertThat(matricula.getDiasTrancados()).isZero();
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2026-11-16"));
        assertThat(matricula.getTrancamentos().getFirst().getVolta()).isEqualTo(LocalDate.parse("2026-11-01"));
        // Desfeito, ele não impede um novo trancamento.
        trancar(matricula, "2026-11-02", "2026-11-09", "2026-10-25T10:00");
        assertThat(matricula.getDiasTrancados()).isEqualTo(7);
    }

    @Test
    void voltaSemTrancamentoEmAbertoEhRecusada() {
        Matricula matricula = vigente();
        assertThatThrownBy(() -> matricula.registrarVolta(USUARIO, em("2026-10-20T10:00")))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("não tem trancamento");

        trancar(matricula, "2026-10-20", "2026-10-27", "2026-10-20T10:00");
        // No dia da volta prevista, o trancamento já terminou.
        assertThatThrownBy(() -> matricula.registrarVolta(USUARIO, em("2026-10-27T10:00")))
                .isInstanceOf(MatriculaRecusadaException.class);
        assertThatThrownBy(() -> matricula.registrarVolta(null, em("2026-10-21T10:00")))
                .isInstanceOf(DadosInvalidosException.class);
        assertThat(matricula.getDiasTrancados()).isEqualTo(7);
    }

    @Test
    void cancelarDuranteOTrancamentoContaSoOsDiasParadosEAplicaAMulta() {
        Matricula matricula = Matricula.nova(ALUNO, anualComFidelidade("20"), null, em("2026-10-17T10:00"));
        trancar(matricula, "2027-01-10", "2027-03-11", "2027-01-05T10:00");  // 60 dias

        matricula.cancelar(USUARIO, "Não vai voltar", em("2027-01-20T10:00"));  // 10 dias parados

        assertThat(matricula.situacao(em("2027-01-20T10:00"))).isEqualTo(SituacaoMatricula.CANCELADA);
        assertThat(matricula.getDiasTrancados()).isEqualTo(10);
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2027-10-26"));
        assertThat(matricula.getTrancamentos().getFirst().getVolta()).isEqualTo(LocalDate.parse("2027-01-20"));
        // De 21/01 até 27/10 (fim + 1): 9 meses inteiros. 9 × 99,90 × 20% = 179,82.
        assertThat(matricula.getMulta()).hasToString("179.82");
    }

    @Test
    void cancelarComTrancamentoAgendadoODesfaz() {
        Matricula matricula = vigente();
        trancar(matricula, "2026-11-01", "2026-11-15", "2026-10-20T10:00");

        matricula.cancelar(USUARIO, "Desistiu", em("2026-10-25T10:00"));

        assertThat(matricula.getDiasTrancados()).isZero();
        assertThat(matricula.getFim()).isEqualTo(LocalDate.parse("2026-11-16"));
    }

    // Troca de plano (ADR 0018)

    private static Matricula novaDoPlano(boolean fidelidade, String valor) {
        PlanoContratado plano = new PlanoContratado(UUID.randomUUID(), "Novo", new BigDecimal(valor), 12, fidelidade,
                fidelidade ? new BigDecimal("10") : null, FormaPagamento.MENSAL);
        return Matricula.nova(ALUNO, plano, null, em("2027-01-20T10:00"));
    }

    private static Matricula novaDoPlano(boolean fidelidade, String valor, int meses, String percentual) {
        PlanoContratado plano = new PlanoContratado(UUID.randomUUID(), "Novo", new BigDecimal(valor), meses, fidelidade,
                fidelidade ? new BigDecimal(percentual) : null, FormaPagamento.MENSAL);
        return Matricula.nova(ALUNO, plano, null, em("2027-01-20T10:00"));
    }

    /**
     * Atual: anual com 20%, trocada em 20/01 (faltam 8 meses: a multa de hoje seria
     * 8 × 99,90 × 20% = 159,84). Sem multa só se a nova, saindo logo depois da troca, cobrar
     * pelo menos isso (ADR 0020).
     */
    @ParameterizedTest(name = "novo: fidelidade={0}, R$ {1}, {2} meses, {3}% -> multa {4}")
    @CsvSource(nullValues = "null", value = {
            "true,  99.90,  12, 20,   null",    // mesmo plano em outra versão: 11 × 99,90 × 20% = 219,78
            "true,  129.90, 12, 20,   null",    // mais caro, mesmo percentual
            "true,  89.90,  12, 30,   null",    // mais barato, mas compromisso maior: 296,67
            "true,  99.90,   9, 20,   null",    // fronteira: 8 × 99,90 × 20% = 159,84, igual
            "true,  99.90,   9, 19.99, 159.84", // um centavo de compromisso a menos
            "true,  99.90,  12, 10,   159.84",  // mesmo valor, metade do percentual: 109,89
            "true,  129.90,  1, 10,   159.84",  // brecha da revisão: mensal com fidelidade (0 meses restantes)
            "true,  99.90,   6, 0.01, 159.84",  // brecha da revisão: multa simbólica (0,05)
            "false, 129.90, 12, 0,    159.84",  // sem fidelidade: a brecha do ADR 0018
    })
    void multaNaTrocaDeUmaMatriculaComFidelidade(boolean fidelidadeNova, String valorNovo, int meses,
                                                  String percentual, String multa) {
        Matricula atual = Matricula.nova(ALUNO, anualComFidelidade("20"), null, em("2026-10-17T10:00"));
        Matricula nova = novaDoPlano(fidelidadeNova, valorNovo, meses, percentual);

        atual.encerrarPorTroca(nova, USUARIO, em("2027-01-20T10:00"));

        assertThat(atual.getMulta() == null ? null : atual.getMulta().toString()).isEqualTo(multa);
    }

    @Test
    void trocaNoUltimoMesNaoTemMulta() {
        // Faltam 0 meses inteiros: a multa de hoje seria 0, e qualquer plano novo mantém isso.
        Matricula atual = Matricula.nova(ALUNO, anualComFidelidade("20"), null, em("2026-10-17T10:00"));

        atual.encerrarPorTroca(novaDoPlano(false, "50", 1, "0"), USUARIO, em("2027-10-01T10:00"));

        assertThat(atual.getMulta()).isNull();
    }

    @Test
    void trocaDeMatriculaSemFidelidadeNuncaTemMulta() {
        Matricula atual = vigente();

        atual.encerrarPorTroca(novaDoPlano(false, "50"), USUARIO, em("2026-10-20T10:00"));

        assertThat(atual.getMulta()).isNull();
    }

    @Test
    void trocaEncerraNaVesperaELigaANova() {
        Matricula atual = vigente();
        Matricula nova = novaDoPlano(false, "150");

        atual.encerrarPorTroca(nova, USUARIO, em("2026-10-20T10:00"));

        assertThat(atual.situacao(em("2026-10-20T10:00"))).isEqualTo(SituacaoMatricula.TROCADA);
        assertThat(atual.getTrocadaEm()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(atual.getTrocadaPor()).isEqualTo(USUARIO);
        assertThat(atual.getFim()).isEqualTo(LocalDate.parse("2026-11-16"));  // o contratado continua registrado
        assertThat(nova.getTrocaDe()).isEqualTo(atual.getId());
        assertThat(atual.getTrocaDe()).isNull();
        // Trocada é final.
        assertThatThrownBy(() -> atual.cancelar(USUARIO, "Motivo", em("2026-10-21T10:00")))
                .isInstanceOf(MatriculaNaoCancelavelException.class).hasMessageContaining("trocada");
        assertThatThrownBy(() -> trancar(atual, null, "2026-11-01", "2026-10-21T10:00"))
                .isInstanceOf(MatriculaRecusadaException.class);
    }

    /**
     * A nova de uma troca continua a anterior (ADR 0026): começa hoje, mas as parcelas e os
     * meses do plano contam do próximo vencimento da anterior. O fim vai junto.
     */
    @Test
    void continuacaoEstendeOFimPelosDiasJaPagos() {
        Matricula nova = novaDoPlano(false, "150");  // 12 meses, a partir de 20/01/2027

        nova.continuar(new MatriculaParaCobranca.Continuacao(LocalDate.parse("2027-02-17")));

        assertThat(nova.getInicio()).isEqualTo(LocalDate.parse("2027-01-20"));
        assertThat(nova.getPrimeiroVencimento()).isEqualTo(LocalDate.parse("2027-02-17"));
        assertThat(nova.getFim()).isEqualTo(LocalDate.parse("2028-02-16"));
        assertThat(nova.paraCobranca().primeiroVencimento()).isEqualTo(LocalDate.parse("2027-02-17"));
        assertThat(nova.situacao(em("2027-01-20T10:00"))).isEqualTo(SituacaoMatricula.VIGENTE);
    }

    @Test
    void continuacaoNoProprioInicioNaoMudaNada() {
        Matricula nova = novaDoPlano(false, "150");
        LocalDate fim = nova.getFim();

        nova.continuar(new MatriculaParaCobranca.Continuacao(nova.getInicio()));

        assertThat(nova.getPrimeiroVencimento()).isEqualTo(nova.getInicio());
        assertThat(nova.getFim()).isEqualTo(fim);
    }

    @Test
    void continuacaoForaDoPrimeiroMesOuDeMatriculaJaLigadaEhRecusada() {
        Matricula nova = novaDoPlano(false, "150");
        assertThatThrownBy(() -> nova.continuar(new MatriculaParaCobranca.Continuacao(LocalDate.parse("2027-01-19"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> nova.continuar(new MatriculaParaCobranca.Continuacao(LocalDate.parse("2027-02-21"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> nova.continuar(new MatriculaParaCobranca.Continuacao(LocalDate.parse("2027-02-20"))))
                .doesNotThrowAnyException();

        Matricula ligada = novaDoPlano(false, "150");
        Matricula.nova(ALUNO, plano("100", 3), null, em("2027-01-01T10:00"))
                .encerrarPorTroca(ligada, USUARIO, em("2027-01-20T10:00"));
        assertThatThrownBy(() -> ligada.continuar(new MatriculaParaCobranca.Continuacao(LocalDate.parse("2027-02-01"))))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * O crédito da anterior (ADR 0026) fica na nova e vai para a cobrança. Maior que o plano novo
     * inteiro, a troca é recusada (decisão do dono), sem mudar nada; igual, o plano sai pago.
     */
    @Test
    void continuacaoLevaOCreditoERecusaOMaiorQueOPlano() {
        Matricula nova = novaDoPlano(false, "150");  // 12 × 150 = 1.800
        LocalDate fim = nova.getFim();
        var demais = new MatriculaParaCobranca.Continuacao(LocalDate.parse("2027-02-17"), new BigDecimal("1800.01"));
        assertThatThrownBy(() -> nova.continuar(demais))
                .isInstanceOf(MatriculaRecusadaException.class)
                .hasMessageContaining("R$ 1.800,01").hasMessageContaining("R$ 1.800,00");
        assertThat(nova.getCredito()).isZero();
        assertThat(nova.getPrimeiroVencimento()).isEqualTo(nova.getInicio());
        assertThat(nova.getFim()).isEqualTo(fim);

        nova.continuar(new MatriculaParaCobranca.Continuacao(LocalDate.parse("2027-02-17"), new BigDecimal("1800.00")));
        assertThat(nova.getCredito()).isEqualByComparingTo("1800");
        assertThat(nova.paraCobranca().credito()).isEqualByComparingTo("1800");

        Matricula semCredito = novaDoPlano(false, "150");
        semCredito.continuar(new MatriculaParaCobranca.Continuacao(LocalDate.parse("2027-02-17")));
        assertThat(semCredito.getCredito()).isZero();
        assertThat(semCredito.paraCobranca().credito()).isZero();
    }

    @Test
    void trocaRecusadaComTrancamentoEmAberto() {
        Matricula trancada = vigente();
        trancar(trancada, "2026-10-20", "2026-10-30", "2026-10-20T10:00");
        assertThatThrownBy(() -> trancada.encerrarPorTroca(novaDoPlano(false, "150"), USUARIO, em("2026-10-22T10:00")))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("registre a volta");

        Matricula comTrancamentoAgendado = vigente();
        trancar(comTrancamentoAgendado, "2026-11-01", "2026-11-10", "2026-10-20T10:00");
        assertThatThrownBy(() -> comTrancamentoAgendado.encerrarPorTroca(novaDoPlano(false, "150"), USUARIO,
                em("2026-10-22T10:00")))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("registre a volta");
        assertThat(comTrancamentoAgendado.getTrocadaEm()).isNull();
    }

    @Test
    void soTrocaMatriculaVigenteParaOutroPlano() {
        Matricula agendada = mensal("2026-11-01", "2026-10-17T10:00");
        assertThatThrownBy(() -> agendada.encerrarPorTroca(novaDoPlano(false, "150"), USUARIO, em("2026-10-20T10:00")))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("agendada");

        Matricula encerrada = vigente();
        assertThatThrownBy(() -> encerrada.encerrarPorTroca(novaDoPlano(false, "150"), USUARIO, em("2026-11-20T10:00")))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("encerrada");

        Matricula atual = vigente();
        Matricula mesmoPlano = Matricula.nova(ALUNO, new PlanoContratado(atual.getPlanoId(), "Mensal",
                new BigDecimal("129.90"), 1), null, em("2026-10-20T10:00"));
        assertThatThrownBy(() -> atual.encerrarPorTroca(mesmoPlano, USUARIO, em("2026-10-20T10:00")))
                .isInstanceOf(MatriculaRecusadaException.class).hasMessageContaining("já é deste plano");
        assertThatThrownBy(() -> atual.encerrarPorTroca(novaDoPlano(false, "150"), null, em("2026-10-20T10:00")))
                .isInstanceOf(DadosInvalidosException.class);
        assertThat(atual.getTrocadaEm()).isNull();
        assertThat(atual.situacao(em("2026-10-20T10:00"))).isEqualTo(SituacaoMatricula.VIGENTE);
    }
}
