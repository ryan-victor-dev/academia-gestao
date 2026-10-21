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
 */
public record MatriculaParaCobranca(UUID matriculaId, UUID alunoId, BigDecimal valorMensal, int duracaoMeses,
                                    boolean aVista, LocalDate inicio, List<Pausa> pausas,
                                    Encerramento encerramento, LocalDate primeiroVencimento) {

    public MatriculaParaCobranca {
        pausas = List.copyOf(pausas);
        if (primeiroVencimento == null) {
            primeiroVencimento = inicio;
        }
    }

    /** As parcelas contam do início. */
    public MatriculaParaCobranca(UUID matriculaId, UUID alunoId, BigDecimal valorMensal, int duracaoMeses,
                                 boolean aVista, LocalDate inicio, List<Pausa> pausas, Encerramento encerramento) {
        this(matriculaId, alunoId, valorMensal, duracaoMeses, aVista, inicio, pausas, encerramento, null);
    }

    /**
     * Como a matrícula nova de uma troca continua esta (ADR 0026): as parcelas dela começam no
     * próximo vencimento desta, porque até lá o aluno já pagou (o mês em curso conta como usado).
     */
    public record Continuacao(LocalDate primeiroVencimento) {
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
