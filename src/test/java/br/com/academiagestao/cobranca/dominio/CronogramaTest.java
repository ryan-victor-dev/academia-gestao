package br.com.academiagestao.cobranca.dominio;

import br.com.academiagestao.cobranca.dominio.MatriculaParaCobranca.Encerramento;
import br.com.academiagestao.cobranca.dominio.MatriculaParaCobranca.Pausa;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As cobranças de uma matrícula, recalculadas a cada mudança (ADR 0024). */
class CronogramaTest {

    private static final UUID MATRICULA = UUID.randomUUID();
    private static final UUID ALUNO = UUID.randomUUID();
    private static final LocalDate INICIO = LocalDate.of(2026, 10, 17);
    private static final Instant AGORA = Instant.parse("2026-10-17T12:00:00Z");

    private static MatriculaParaCobranca mensal(int meses, List<Pausa> pausas, Encerramento encerramento) {
        return new MatriculaParaCobranca(MATRICULA, ALUNO, new BigDecimal("100.00"), meses, false, INICIO,
                pausas, encerramento);
    }

    private static List<LocalDate> vencimentos(List<ParcelaPrevista> parcelas) {
        return parcelas.stream().map(ParcelaPrevista::vencimento).toList();
    }

    private static LocalDate dia(int ano, int mes, int dia) {
        return LocalDate.of(ano, mes, dia);
    }

    @Test
    void mensalUmaPorMesNoDiaDoInicio() {
        List<ParcelaPrevista> parcelas = Cronograma.previstas(mensal(3, List.of(), null));

        assertThat(parcelas).containsExactly(
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, new BigDecimal("100.00"), dia(2026, 10, 17)),
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 2, new BigDecimal("100.00"), dia(2026, 11, 17)),
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 3, new BigDecimal("100.00"), dia(2026, 12, 17)));
    }

    /** Sem o dia no mês, vence no último dia dele, e o dia 31 volta nos meses que o têm. */
    @Test
    void diaQueNaoExisteNoMes() {
        var matricula = new MatriculaParaCobranca(MATRICULA, ALUNO, BigDecimal.TEN, 3, false, dia(2027, 1, 31),
                List.of(), null);
        assertThat(vencimentos(Cronograma.previstas(matricula)))
                .containsExactly(dia(2027, 1, 31), dia(2027, 2, 28), dia(2027, 3, 31));
    }

    @Test
    void aVistaUmaCobrancaComOTotalNoInicio() {
        var matricula = new MatriculaParaCobranca(MATRICULA, ALUNO, new BigDecimal("99.90"), 12, true, INICIO,
                List.of(), null);
        assertThat(Cronograma.previstas(matricula)).containsExactly(
                new ParcelaPrevista(TipoCobranca.A_VISTA, 1, new BigDecimal("1198.80"), INICIO));
    }

    /** Trancamento: as mensalidades pausam e são empurradas pelos dias parados (decisão do dono). */
    @Test
    void trancamentoEmpurraAsSeguintes() {
        var matricula = mensal(3, List.of(new Pausa(dia(2026, 10, 20), 30)), null);
        assertThat(vencimentos(Cronograma.previstas(matricula)))
                .containsExactly(dia(2026, 10, 17), dia(2026, 12, 17), dia(2027, 1, 16));
    }

    @Test
    void trancamentoQueComecaNoVencimentoEmpurraEle() {
        assertThat(vencimentos(Cronograma.previstas(mensal(2, List.of(new Pausa(dia(2026, 11, 17), 10)), null))))
                .containsExactly(dia(2026, 10, 17), dia(2026, 11, 27));
        assertThat(vencimentos(Cronograma.previstas(mensal(2, List.of(new Pausa(dia(2026, 11, 18), 10)), null))))
                .containsExactly(dia(2026, 10, 17), dia(2026, 11, 17));
    }

    /** O segundo trancamento começa antes do vencimento já adiado pelo primeiro: soma os dois. */
    @Test
    void trancamentosSeguidosSeSomam() {
        List<Pausa> pausas = List.of(new Pausa(dia(2026, 11, 25), 10), new Pausa(dia(2026, 11, 1), 10));
        assertThat(vencimentos(Cronograma.previstas(mensal(2, pausas, null))))
                .containsExactly(dia(2026, 10, 17), dia(2026, 12, 7));
    }

    @Test
    void cancelamentoMantemAsAteODiaEAMulta() {
        var encerramento = new Encerramento(Encerramento.Tipo.CANCELAMENTO, dia(2026, 11, 17), new BigDecimal("50"));
        List<ParcelaPrevista> parcelas = Cronograma.previstas(mensal(12, List.of(), encerramento));
        assertThat(parcelas).containsExactly(
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, new BigDecimal("100.00"), dia(2026, 10, 17)),
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 2, new BigDecimal("100.00"), dia(2026, 11, 17)),
                new ParcelaPrevista(TipoCobranca.MULTA, 1, new BigDecimal("50"), dia(2026, 11, 17)));
    }

    @Test
    void canceladaAntesDeComecarNaoTemCobranca() {
        var encerramento = new Encerramento(Encerramento.Tipo.CANCELAMENTO, INICIO.minusDays(1), null);
        assertThat(Cronograma.previstas(mensal(3, List.of(), encerramento))).isEmpty();
    }

    /** A nova matrícula começa no dia da troca, com a primeira mensalidade dela. */
    @Test
    void trocaMantemAsDeAntesDoDia() {
        var semMulta = new Encerramento(Encerramento.Tipo.TROCA, dia(2026, 11, 17), BigDecimal.ZERO);
        assertThat(Cronograma.previstas(mensal(12, List.of(), semMulta))).containsExactly(
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 1, new BigDecimal("100.00"), dia(2026, 10, 17)));
        var comMulta = new Encerramento(Encerramento.Tipo.TROCA, dia(2026, 11, 18), new BigDecimal("0.01"));
        assertThat(vencimentos(Cronograma.previstas(mensal(12, List.of(), comMulta))))
                .containsExactly(dia(2026, 10, 17), dia(2026, 11, 17), dia(2026, 11, 18));
    }

    @Test
    void criaTodasNaMatriculaNova() {
        Cronograma.Acerto acerto = Cronograma.acertar(mensal(3, List.of(), null), List.of(), INICIO, AGORA);

        assertThat(acerto.alteradas()).isEmpty();
        assertThat(acerto.novas()).extracting(Cobranca::getParcela).containsExactly(1, 2, 3);
        assertThat(acerto.novas()).allSatisfy(c -> {
            assertThat(c.getMatriculaId()).isEqualTo(MATRICULA);
            assertThat(c.getAlunoId()).isEqualTo(ALUNO);
            assertThat(c.getStatus()).isEqualTo(StatusCobranca.PENDENTE);
        });
    }

    /** Matrícula que já existia antes da cobrança: só as de "a partir de" em diante (decisão do dono). */
    @Test
    void soAsQueVencemAPartirDoDia() {
        Cronograma.Acerto acerto = Cronograma.acertar(mensal(3, List.of(), null), List.of(), dia(2026, 11, 17), AGORA);
        assertThat(acerto.novas()).extracting(Cobranca::getParcela).containsExactly(2, 3);
    }

    @Test
    void nadaMudaNadaAltera() {
        var matricula = mensal(3, List.of(), null);
        List<Cobranca> gravadas = Cronograma.acertar(matricula, List.of(), INICIO, AGORA).novas();

        Cronograma.Acerto acerto = Cronograma.acertar(matricula, gravadas, INICIO, AGORA);

        assertThat(acerto.novas()).isEmpty();
        assertThat(acerto.alteradas()).isEmpty();
    }

    @Test
    void trancamentoReprogramaAsGravadas() {
        List<Cobranca> gravadas = Cronograma.acertar(mensal(3, List.of(), null), List.of(), INICIO, AGORA).novas();
        var trancada = mensal(3, List.of(new Pausa(dia(2026, 10, 20), 30)), null);

        Cronograma.Acerto acerto = Cronograma.acertar(trancada, gravadas, INICIO, AGORA);

        assertThat(acerto.novas()).isEmpty();
        assertThat(acerto.alteradas()).extracting(Cobranca::getParcela).containsExactly(2, 3);
        assertThat(gravadas).extracting(Cobranca::getVencimento)
                .containsExactly(dia(2026, 10, 17), dia(2026, 12, 17), dia(2027, 1, 16));
    }

    @Test
    void encerramentoCancelaAsEmAbertoECriaAMulta() {
        List<Cobranca> gravadas = Cronograma.acertar(mensal(3, List.of(), null), List.of(), INICIO, AGORA).novas();
        Cobranca pagaAdiantada = gravadas.get(2);
        pagaAdiantada.pagar(RegrasDeAtraso.PADRAO, INICIO, new BigDecimal("100"), MeioDePagamento.PIX,
                UUID.randomUUID(), AGORA);
        var cancelada = mensal(3, List.of(), new Encerramento(Encerramento.Tipo.CANCELAMENTO, INICIO,
                new BigDecimal("80")));

        Cronograma.Acerto acerto = Cronograma.acertar(cancelada, gravadas, INICIO, AGORA);

        assertThat(acerto.novas()).singleElement().satisfies(multa -> {
            assertThat(multa.getTipo()).isEqualTo(TipoCobranca.MULTA);
            assertThat(multa.getValor()).isEqualByComparingTo("80");
            assertThat(multa.getVencimento()).isEqualTo(INICIO);
        });
        assertThat(acerto.alteradas()).containsExactly(gravadas.get(1), pagaAdiantada);
        assertThat(gravadas.get(0).getStatus()).isEqualTo(StatusCobranca.PENDENTE);
        assertThat(gravadas.get(1).getStatus()).isEqualTo(StatusCobranca.CANCELADA);
        assertThat(gravadas.get(1).getCancelamento().motivo()).isEqualTo(Cronograma.MOTIVO_DO_ENCERRAMENTO);
        assertThat(gravadas.get(1).getCancelamento().por()).isNull();
        // Paga adiantada continua paga: a devolução, se houver, é por fora (decisão do dono).
        // Fica marcada fora do cronograma: um estorno a cancela (ADR 0026).
        assertThat(pagaAdiantada.getStatus()).isEqualTo(StatusCobranca.PAGA);
        assertThat(pagaAdiantada.isForaDoCronograma()).isTrue();
        assertThat(gravadas.get(0).isForaDoCronograma()).isFalse();

        // De novo, nada muda: a marcada não volta a ser alterada.
        assertThat(Cronograma.acertar(cancelada, gravadas, INICIO, AGORA).alteradas()).isEmpty();
    }

    /** Paga e ainda no cronograma não é marcada. */
    @Test
    void pagaNoCronogramaNaoEhMarcada() {
        List<Cobranca> gravadas = Cronograma.acertar(mensal(2, List.of(), null), List.of(), INICIO, AGORA).novas();
        gravadas.getFirst().pagar(RegrasDeAtraso.PADRAO, INICIO, new BigDecimal("100"), MeioDePagamento.PIX,
                UUID.randomUUID(), AGORA);

        Cronograma.Acerto acerto = Cronograma.acertar(mensal(2, List.of(), null), gravadas, INICIO, AGORA);

        assertThat(acerto.alteradas()).isEmpty();
        assertThat(gravadas.getFirst().isForaDoCronograma()).isFalse();
    }

    /** Cancelada pelo ADMIN não volta, nem é reprogramada, quando o cronograma muda. */
    @Test
    void canceladaNuncaVolta() {
        List<Cobranca> gravadas = Cronograma.acertar(mensal(2, List.of(), null), List.of(), INICIO, AGORA).novas();
        gravadas.get(1).cancelar("Bolsa", UUID.randomUUID(), AGORA);
        var trancada = mensal(2, List.of(new Pausa(dia(2026, 10, 20), 30)), null);

        Cronograma.Acerto acerto = Cronograma.acertar(trancada, gravadas, INICIO, AGORA);

        assertThat(acerto.novas()).isEmpty();
        assertThat(acerto.alteradas()).isEmpty();
        assertThat(gravadas.get(1).getVencimento()).isEqualTo(dia(2026, 11, 17));
    }

    /** Gravada antes de "a partir de" continua sendo acertada (só a criação respeita o dia). */
    @Test
    void gravadaAntesDoDiaContinuaAcertada() {
        List<Cobranca> gravadas = Cronograma.acertar(mensal(2, List.of(), null), List.of(), INICIO, AGORA).novas();
        var trancada = mensal(2, List.of(new Pausa(INICIO, 10)), null);

        Cronograma.Acerto acerto = Cronograma.acertar(trancada, gravadas, dia(2026, 12, 1), AGORA);

        assertThat(acerto.alteradas()).hasSize(2);
        assertThat(gravadas.get(0).getVencimento()).isEqualTo(dia(2026, 10, 27));
    }

    // Troca de plano: de onde contam as parcelas da nova (decisão do dono, ADR 0026).

    /** No meio do mês: o mês em curso já foi cobrado pela antiga; a nova começa no próximo vencimento. */
    @Test
    void continuacaoNoMeioDoMesComecaNoProximoVencimento() {
        assertThat(Cronograma.continuacao(mensal(3, List.of(), null), List.of(), dia(2026, 11, 1))
                .primeiroVencimento()).isEqualTo(dia(2026, 11, 17));
    }

    /** No dia de um vencimento: a mensalidade do dia sai da antiga, e a nova começa nele. */
    @Test
    void continuacaoNoDiaDoVencimentoComecaNele() {
        assertThat(Cronograma.continuacao(mensal(3, List.of(), null), List.of(), dia(2026, 11, 17))
                .primeiroVencimento()).isEqualTo(dia(2026, 11, 17));
        assertThat(Cronograma.continuacao(mensal(3, List.of(), null), List.of(), INICIO).primeiroVencimento())
                .isEqualTo(INICIO);
    }

    /** No último mês: a nova começa no dia seguinte ao fim da antiga. */
    @Test
    void continuacaoNoUltimoMesComecaDepoisDoFim() {
        assertThat(Cronograma.continuacao(mensal(3, List.of(), null), List.of(), dia(2027, 1, 1))
                .primeiroVencimento()).isEqualTo(dia(2027, 1, 17));
    }

    /** Um trancamento já terminado empurrou os vencimentos: a conta usa os empurrados. */
    @Test
    void continuacaoDepoisDeUmTrancamento() {
        var trancada = mensal(3, List.of(new Pausa(dia(2026, 10, 20), 30)), null);
        assertThat(Cronograma.continuacao(trancada, List.of(), dia(2026, 12, 1)).primeiroVencimento())
                .isEqualTo(dia(2026, 12, 17));
    }

    /** À vista: o total cobre os meses, que começam no mesmo dia de cada mês. */
    @Test
    void continuacaoDeUmaAVista() {
        var aVista = new MatriculaParaCobranca(MATRICULA, ALUNO, new BigDecimal("100.00"), 12, true, INICIO,
                List.of(), null);
        assertThat(Cronograma.continuacao(aVista, List.of(), dia(2027, 1, 5)).primeiroVencimento())
                .isEqualTo(dia(2027, 1, 17));
    }

    /** A nova conta as parcelas do primeiro vencimento; o dia 31 continua no último dia do mês. */
    @Test
    void parcelasContamDoPrimeiroVencimento() {
        var continuacao = new MatriculaParaCobranca(MATRICULA, ALUNO, new BigDecimal("100.00"), 3, false,
                dia(2026, 12, 20), List.of(), null, dia(2027, 1, 31), null);
        assertThat(vencimentos(Cronograma.previstas(continuacao)))
                .containsExactly(dia(2027, 1, 31), dia(2027, 2, 28), dia(2027, 3, 31));
        assertThat(Cronograma.continuacao(continuacao, List.of(), dia(2027, 2, 10)).primeiroVencimento())
                .isEqualTo(dia(2027, 2, 28));

        var aVista = new MatriculaParaCobranca(MATRICULA, ALUNO, new BigDecimal("100.00"), 3, true,
                dia(2026, 12, 20), List.of(), null, dia(2027, 1, 5), null);
        assertThat(vencimentos(Cronograma.previstas(aVista))).containsExactly(dia(2027, 1, 5));
        // Sem o primeiro vencimento, é o início.
        assertThat(mensal(1, List.of(), null).primeiroVencimento()).isEqualTo(INICIO);
    }

    // O que foi pago e não usado vira crédito na nova (decisão do dono, ADR 0026).

    private static MatriculaParaCobranca comCredito(boolean aVista, String credito) {
        return new MatriculaParaCobranca(MATRICULA, ALUNO, new BigDecimal("100.00"), 3, aVista, INICIO, List.of(),
                null, null, new BigDecimal(credito));
    }

    private static Cobranca gravada(TipoCobranca tipo, int parcela, String valor, LocalDate vencimento) {
        return Cobranca.nova(MATRICULA, ALUNO, new ParcelaPrevista(tipo, parcela, new BigDecimal(valor), vencimento));
    }

    /** À vista de 12 meses trocada no 4º mês: 3 meses usados, 9 de crédito. */
    @Test
    void aVistaTrocadaNoMeioDeixaOsMesesNaoUsadosDeCredito() {
        var aVista = new MatriculaParaCobranca(MATRICULA, ALUNO, new BigDecimal("100.00"), 12, true, INICIO,
                List.of(), null);
        var continuacao = Cronograma.continuacao(aVista,
                List.of(gravada(TipoCobranca.A_VISTA, 1, "1200.00", INICIO)), dia(2027, 1, 5));

        assertThat(continuacao.primeiroVencimento()).isEqualTo(dia(2027, 1, 17));
        assertThat(continuacao.credito()).isEqualByComparingTo("900");
    }

    /**
     * Só o cobrado que fica com a antiga conta: nem a cancelada, nem a multa, nem a que vence
     * no dia da troca ou depois (sai da antiga). Sem cobrado, sem crédito (nunca negativo).
     */
    @Test
    void soOCobradoQueFicaViraCredito() {
        var aVista = new MatriculaParaCobranca(MATRICULA, ALUNO, new BigDecimal("100.00"), 12, true, INICIO,
                List.of(), null);
        Cobranca cancelada = gravada(TipoCobranca.A_VISTA, 1, "1200.00", INICIO);
        cancelada.cancelar("cortesia", UUID.randomUUID(), AGORA);
        assertThat(Cronograma.continuacao(aVista, List.of(cancelada), dia(2027, 1, 5)).credito()).isZero();

        Cobranca multa = gravada(TipoCobranca.MULTA, 1, "500.00", INICIO);
        assertThat(Cronograma.continuacao(aVista, List.of(multa), dia(2027, 1, 5)).credito()).isZero();

        // No dia do vencimento da à vista (o primeiro), ela sai da antiga: nada foi usado nem fica.
        assertThat(Cronograma.continuacao(aVista, List.of(gravada(TipoCobranca.A_VISTA, 1, "1200.00", INICIO)),
                INICIO).credito()).isZero();

        // Mensal em dia: cobrado = usado.
        var mensal = mensal(3, List.of(), null);
        List<Cobranca> parcelas = Cronograma.previstas(mensal).stream()
                .map(p -> Cobranca.nova(MATRICULA, ALUNO, p)).toList();
        assertThat(Cronograma.continuacao(mensal, parcelas, dia(2026, 12, 1)).credito()).isZero();
    }

    /** O crédito abate as primeiras parcelas, em ordem; a zerada não é cobrada. */
    @Test
    void creditoAbateAsPrimeirasParcelas() {
        assertThat(Cronograma.previstas(comCredito(false, "250.00"))).containsExactly(
                new ParcelaPrevista(TipoCobranca.MENSALIDADE, 3, new BigDecimal("50.00"), dia(2026, 12, 17)));
        assertThat(Cronograma.previstas(comCredito(false, "30.00"))).extracting(ParcelaPrevista::valor)
                .containsExactly(new BigDecimal("70.00"), new BigDecimal("100.00"), new BigDecimal("100.00"));
        assertThat(Cronograma.previstas(comCredito(true, "250.00"))).containsExactly(
                new ParcelaPrevista(TipoCobranca.A_VISTA, 1, new BigDecimal("50.00"), INICIO));
        assertThat(Cronograma.previstas(comCredito(true, "300.00"))).isEmpty();
        assertThat(Cronograma.previstas(comCredito(false, "300.00"))).isEmpty();
    }

    /** Trocada de novo: leva o crédito que sobrou (o recebido menos os meses usados). */
    @Test
    void creditoQueSobraPassaParaAProxima() {
        var comCredito = comCredito(false, "250.00");
        List<Cobranca> parcelas = Cronograma.previstas(comCredito).stream()
                .map(p -> Cobranca.nova(MATRICULA, ALUNO, p)).toList();

        // Dois meses usados (200), e a parcela 3 (50) sai da antiga: sobram 50.
        assertThat(Cronograma.continuacao(comCredito, parcelas, dia(2026, 12, 1)).credito())
                .isEqualByComparingTo("50");
        // Três meses usados: o crédito acabou, e a parcela 3 (50) foi cobrada.
        assertThat(Cronograma.continuacao(comCredito, parcelas, dia(2027, 1, 10)).credito()).isZero();
    }

    @Test
    void continuacaoNaoAceitaCreditoNegativo() {
        assertThatThrownBy(() -> new MatriculaParaCobranca.Continuacao(INICIO, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatriculaParaCobranca.Continuacao(INICIO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
