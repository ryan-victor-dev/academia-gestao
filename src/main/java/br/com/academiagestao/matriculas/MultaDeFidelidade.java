package br.com.academiagestao.matriculas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * A multa de fidelidade (ADRs 0018 e 0020), em Java puro. Saiu da {@link Matricula} (R1): ela
 * decide quando há multa (cancelamento, troca) e guarda o valor; aqui fica só a conta.
 */
final class MultaDeFidelidade {

    private static final BigDecimal CEM = new BigDecimal("100");

    private MultaDeFidelidade() {
    }

    /**
     * Percentual × meses inteiros que faltam × valor mensal. O mês em curso conta como usado:
     * um anual de 17/10 a 16/10 encerrado em 20/01 tem 8 meses restantes (de 17/02 em diante).
     */
    static BigDecimal seEncerrarEm(LocalDate dia, LocalDate fim, BigDecimal valorMensal, BigDecimal percentual) {
        // Conta a partir do dia seguinte: um mês que começa no próprio dia já está em curso.
        long mesesRestantes = ChronoUnit.MONTHS.between(dia.plusDays(1), fim.plusDays(1));
        return valorMensal.multiply(BigDecimal.valueOf(mesesRestantes))
                .multiply(percentual)
                .divide(CEM, 2, RoundingMode.HALF_UP);
    }

    /**
     * A troca não tem multa só se o compromisso continuar (ADR 0020): a multa que a nova
     * cobraria se o aluno saísse logo depois da troca é pelo menos a que a atual cobraria hoje.
     *
     * @param multaDaAtual {@code null} = a atual não tem fidelidade
     * @param multaDaNova  zero quando a nova não tem fidelidade
     */
    static boolean trocaMantemOCompromisso(BigDecimal multaDaAtual, BigDecimal multaDaNova) {
        return multaDaAtual == null || multaDaNova.compareTo(multaDaAtual) >= 0;
    }
}
