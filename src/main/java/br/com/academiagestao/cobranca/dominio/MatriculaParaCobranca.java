package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * O que a cobrança precisa saber de uma matrícula (porta pública do módulo): a matrícula
 * manda estes dados a cada mudança, e a cobrança recalcula o cronograma (ADR 0024).
 *
 * @param aVista       uma cobrança com o total no início, em vez de uma por mês
 * @param pausas       os trancamentos, com os dias realmente parados
 * @param encerramento cancelamento ou troca; {@code null} enquanto não houver
 */
public record MatriculaParaCobranca(UUID matriculaId, UUID alunoId, BigDecimal valorMensal, int duracaoMeses,
                                    boolean aVista, LocalDate inicio, List<Pausa> pausas,
                                    Encerramento encerramento) {

    public MatriculaParaCobranca {
        pausas = List.copyOf(pausas);
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
