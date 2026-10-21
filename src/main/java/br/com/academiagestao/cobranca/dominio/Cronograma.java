package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * As cobranças de uma matrícula, recalculadas do zero a cada mudança dela (ADR 0024): a
 * mesma conta cobre a matrícula nova, o trancamento, a volta, o cancelamento e a troca.
 */
public final class Cronograma {

    static final String MOTIVO_DO_ENCERRAMENTO = "Matrícula encerrada (cancelamento ou troca de plano)";

    private Cronograma() {
    }

    /**
     * Mensal: a parcela k vence em primeiro vencimento + (k − 1) meses (sem o dia no mês, no
     * último dia dele), adiada pelos trancamentos que começaram até lá. À vista: o total, no
     * primeiro vencimento. O primeiro vencimento é o início, salvo na matrícula que veio de uma
     * troca (ver {@link #continuacao}). O crédito dela abate as primeiras parcelas, em ordem; a
     * que fica zerada não é cobrada. Encerrada, só ficam as de antes do encerramento, mais a multa.
     */
    public static List<ParcelaPrevista> previstas(MatriculaParaCobranca matricula) {
        List<ParcelaPrevista> parcelas = new ArrayList<>();
        BigDecimal credito = matricula.credito();
        if (matricula.aVista()) {
            BigDecimal total = matricula.valorMensal().multiply(BigDecimal.valueOf(matricula.duracaoMeses()));
            BigDecimal aPagar = total.subtract(credito);
            if (aPagar.signum() > 0) {
                parcelas.add(new ParcelaPrevista(TipoCobranca.A_VISTA, 1, aPagar, vencimento(matricula, 1)));
            }
        } else {
            for (int k = 1; k <= matricula.duracaoMeses(); k++) {
                BigDecimal abatido = matricula.valorMensal().min(credito);
                credito = credito.subtract(abatido);
                BigDecimal aPagar = matricula.valorMensal().subtract(abatido);
                if (aPagar.signum() > 0) {
                    parcelas.add(new ParcelaPrevista(TipoCobranca.MENSALIDADE, k, aPagar, vencimento(matricula, k)));
                }
            }
        }
        MatriculaParaCobranca.Encerramento encerramento = matricula.encerramento();
        if (encerramento == null) {
            return parcelas;
        }
        List<ParcelaPrevista> ficam = new ArrayList<>(parcelas.stream()
                .filter(p -> encerramento.tipo() == MatriculaParaCobranca.Encerramento.Tipo.CANCELAMENTO
                        ? !p.vencimento().isAfter(encerramento.dia())
                        : p.vencimento().isBefore(encerramento.dia()))
                .toList());
        if (encerramento.multa() != null && encerramento.multa().signum() > 0) {
            ficam.add(new ParcelaPrevista(TipoCobranca.MULTA, 1, encerramento.multa(), encerramento.dia()));
        }
        return ficam;
    }

    /**
     * O k-ésimo mês da matrícula começa (e a mensalidade dele vence) em primeiro vencimento +
     * (k − 1) meses, adiado pelos trancamentos. Sempre a partir do primeiro: 31/01 → 28/02 →
     * 31/03, sem "perder" o dia 31.
     */
    private static LocalDate vencimento(MatriculaParaCobranca matricula, int k) {
        return adiar(matricula.primeiroVencimento().plusMonths(k - 1), matricula.pausas());
    }

    /**
     * Troca de plano no dia {@code dia} (decisões do dono, ADR 0026): os meses desta que já
     * começaram estão pagos (ou cobrados) inteiros, então a nova só cobra a partir do próximo
     * mês desta, o primeiro que começa no dia ou depois. Na troca no último mês, é o dia
     * seguinte ao fim.
     *
     * O crédito é o que foi cobrado desta (das {@code gravadas}, as que ficam com ela depois da
     * troca), mais o crédito que ela já tinha, menos os meses usados: a à vista de 12 meses
     * trocada no 3º deixa 10 meses de crédito. Só o cobrado conta: a cobrança cancelada (ou a que
     * nunca existiu, numa matrícula de antes da cobrança) não vira crédito. Paga ou em aberto,
     * tanto faz: a em aberto continua devida.
     */
    public static MatriculaParaCobranca.Continuacao continuacao(MatriculaParaCobranca antiga, List<Cobranca> gravadas,
                                                                LocalDate dia) {
        int k = 1;
        while (vencimento(antiga, k).isBefore(dia)) {
            k++;
        }
        BigDecimal cobrado = gravadas.stream()
                .filter(c -> c.getTipo() != TipoCobranca.MULTA && c.getStatus() != StatusCobranca.CANCELADA
                        && c.getVencimento().isBefore(dia))
                .map(Cobranca::getValor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal usado = antiga.valorMensal().multiply(BigDecimal.valueOf(k - 1));
        BigDecimal credito = antiga.credito().add(cobrado).subtract(usado).max(BigDecimal.ZERO);
        return new MatriculaParaCobranca.Continuacao(vencimento(antiga, k), credito);
    }

    /** Cada trancamento que começou até o vencimento (já adiado pelos anteriores) o empurra. */
    static LocalDate adiar(LocalDate vencimento, List<MatriculaParaCobranca.Pausa> pausas) {
        LocalDate adiado = vencimento;
        for (MatriculaParaCobranca.Pausa pausa : pausas.stream()
                .sorted(Comparator.comparing(MatriculaParaCobranca.Pausa::inicio)).toList()) {
            if (!pausa.inicio().isAfter(adiado)) {
                adiado = adiado.plusDays(pausa.dias());
            }
        }
        return adiado;
    }

    /** O que mudar no banco: cobranças a criar e cobranças já gravadas que mudaram. */
    public record Acerto(List<Cobranca> novas, List<Cobranca> alteradas) {
    }

    /**
     * Acerta as cobranças gravadas com o cronograma previsto:
     * - prevista e ainda não gravada: cria, se vencer a partir de {@code aPartirDe} (as
     *   matrículas que já existiam antes da cobrança só têm as de dali em diante);
     * - gravada com outro vencimento: reprograma, se não estiver cancelada;
     * - gravada e não prevista mais: cancela, se em aberto. Paga continua paga (ADR 0024), mas
     *   fica marcada fora do cronograma: um estorno a cancela (ADR 0026).
     * Uma cancelada nunca volta: nem a cancelada pelo ADMIN, nem a do encerramento.
     */
    public static Acerto acertar(MatriculaParaCobranca matricula, List<Cobranca> gravadas, LocalDate aPartirDe,
                                 Instant agora) {
        Map<String, Cobranca> porChave = new LinkedHashMap<>();
        for (Cobranca gravada : gravadas) {
            porChave.put(chave(gravada.getTipo(), gravada.getParcela()), gravada);
        }
        List<Cobranca> novas = new ArrayList<>();
        List<Cobranca> alteradas = new ArrayList<>();
        for (ParcelaPrevista prevista : previstas(matricula)) {
            Cobranca gravada = porChave.remove(chave(prevista.tipo(), prevista.parcela()));
            if (gravada == null) {
                if (!prevista.vencimento().isBefore(aPartirDe)) {
                    novas.add(Cobranca.nova(matricula.matriculaId(), matricula.alunoId(), prevista));
                }
            } else if (gravada.getStatus() != StatusCobranca.CANCELADA
                    && !gravada.getVencimento().equals(prevista.vencimento())) {
                gravada.reprogramar(prevista.vencimento());
                alteradas.add(gravada);
            }
        }
        for (Cobranca sobrou : porChave.values()) {
            if (sobrou.getStatus() == StatusCobranca.PENDENTE) {
                sobrou.cancelar(MOTIVO_DO_ENCERRAMENTO, null, agora);
                alteradas.add(sobrou);
            } else if (sobrou.getStatus() == StatusCobranca.PAGA && !sobrou.isForaDoCronograma()) {
                sobrou.sairDoCronograma();
                alteradas.add(sobrou);
            }
        }
        return new Acerto(novas, alteradas);
    }

    private static String chave(TipoCobranca tipo, int parcela) {
        return tipo + "#" + parcela;
    }
}
