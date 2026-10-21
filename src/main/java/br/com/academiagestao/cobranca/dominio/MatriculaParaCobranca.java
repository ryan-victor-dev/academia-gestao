package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * O que a cobrança precisa saber de uma matrícula (porta pública do módulo): a matrícula
 * manda estes dados a cada mudança, e a cobrança recalcula o cronograma (ADR 0024).
 *
 * @param aVista             uma cobrança com o total no início, em vez de uma por mês
 * @param pausas             os trancamentos, com os dias realmente parados
 * @param encerramento       cancelamento ou troca; {@code null} enquanto não houver
 * @param primeiroVencimento de onde contam as parcelas: o início ou, na matrícula que veio de
 *                           uma troca, o próximo vencimento da anterior (ADR 0026)
 * @param credito            na matrícula que veio de uma troca, o que o aluno pagou à anterior
 *                           e não usou: abatido das primeiras parcelas (ADR 0026); zero se não há
 */
public record MatriculaParaCobranca(UUID matriculaId, UUID alunoId, BigDecimal valorMensal, int duracaoMeses,
                                    boolean aVista, LocalDate inicio, List<Pausa> pausas,
                                    Encerramento encerramento, LocalDate primeiroVencimento,
                                    BigDecimal credito) {

    public MatriculaParaCobranca {
        pausas = List.copyOf(pausas);
        if (primeiroVencimento == null) {
            primeiroVencimento = inicio;
        }
        if (credito == null) {
            credito = BigDecimal.ZERO;
        }
    }

    /** As parcelas contam do início, sem crédito. */
    public MatriculaParaCobranca(UUID matriculaId, UUID alunoId, BigDecimal valorMensal, int duracaoMeses,
                                 boolean aVista, LocalDate inicio, List<Pausa> pausas, Encerramento encerramento) {
        this(matriculaId, alunoId, valorMensal, duracaoMeses, aVista, inicio, pausas, encerramento, null, null);
    }

    /**
     * Como a matrícula nova de uma troca continua esta (ADR 0026): as parcelas dela começam no
     * próximo vencimento desta, porque até lá o aluno já pagou (o mês em curso conta como usado),
     * e o que foi cobrado desta além dos meses usados vira crédito nelas.
     */
    public record Continuacao(LocalDate primeiroVencimento, BigDecimal credito) {

        public Continuacao {
            if (credito == null || credito.signum() < 0) {
                throw new IllegalArgumentException("crédito deve ser zero ou positivo");
            }
        }

        /** Sem crédito. */
        public Continuacao(LocalDate primeiroVencimento) {
            this(primeiroVencimento, BigDecimal.ZERO);
        }
    }

    /** Um trancamento: de {@code inicio}, {@code dias} parados. */
    public record Pausa(LocalDate inicio, int dias) {
    }

    /**
     * Cancelamento (ficam as cobranças até o dia, inclusive: o mês em curso conta como usado)
     * ou troca (ficam as de antes do dia: a nova matrícula começa nele). A multa, se houver,
     * vira uma cobrança vencendo no dia.
     */
    public record Encerramento(Tipo tipo, LocalDate dia, BigDecimal multa) {
        public enum Tipo { CANCELAMENTO, TROCA }
    }
}
