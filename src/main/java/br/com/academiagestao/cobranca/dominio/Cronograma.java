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
     * Mensal: a parcela k vence em início + (k − 1) meses (sem o dia no mês, no último dia
     * dele), adiada pelos trancamentos que começaram até lá. À vista: o total, no início.
     * Encerrada, só ficam as de antes do encerramento, mais a multa.
     */
    public static List<ParcelaPrevista> previstas(MatriculaParaCobranca matricula) {
        List<ParcelaPrevista> parcelas = new ArrayList<>();
        if (matricula.aVista()) {
            BigDecimal total = matricula.valorMensal().multiply(BigDecimal.valueOf(matricula.duracaoMeses()));
            parcelas.add(new ParcelaPrevista(TipoCobranca.A_VISTA, 1, total,
                    adiar(matricula.inicio(), matricula.pausas())));
        } else {
            for (int k = 1; k <= matricula.duracaoMeses(); k++) {
                // Sempre a partir do início: 31/01 → 28/02 → 31/03, sem "perder" o dia 31.
                LocalDate vencimento = matricula.inicio().plusMonths(k - 1);
                parcelas.add(new ParcelaPrevista(TipoCobranca.MENSALIDADE, k, matricula.valorMensal(),
                        adiar(vencimento, matricula.pausas())));
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
