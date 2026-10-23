package br.com.academiagestao.matriculas;

import br.com.academiagestao.compartilhado.DadosInvalidosException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * As datas de um trancamento novo (ADR 0018): começa entre hoje e o fim da matrícula, dura
 * de 7 a 90 dias e, somado aos anteriores, não passa de 90. Saiu da {@link Matricula} (R1):
 * ela continua decidindo se pode trancar (situação, outro trancamento em aberto) e guarda o
 * resultado; aqui fica só a conta, em Java puro.
 */
final class RegrasDoTrancamento {

    static final int DIAS_MINIMOS = 7;
    static final int DIAS_MAXIMOS_SOMADOS = 90;

    private RegrasDoTrancamento() {
    }

    /** O dia em que o trancamento começa e quantos dias ele para a matrícula. */
    record Periodo(LocalDate inicio, int dias) {
    }

    /**
     * @param inicio          dia pedido; {@code null} = hoje
     * @param diasJaTrancados soma dos trancamentos anteriores desta matrícula
     */
    static Periodo conferir(LocalDate hoje, LocalDate inicio, LocalDate voltaPrevista, LocalDate fimDaMatricula,
                            int diasJaTrancados) {
        LocalDate comeco = inicio == null ? hoje : inicio;
        if (comeco.isBefore(hoje) || comeco.isAfter(fimDaMatricula)) {
            throw new DadosInvalidosException("o trancamento começa entre hoje e o fim da matrícula");
        }
        if (voltaPrevista == null) {
            throw new DadosInvalidosException("a volta prevista é obrigatória");
        }
        long dias = ChronoUnit.DAYS.between(comeco, voltaPrevista);
        if (dias < DIAS_MINIMOS || dias > DIAS_MAXIMOS_SOMADOS) {
            throw new DadosInvalidosException("o trancamento precisa ter de " + DIAS_MINIMOS
                    + " a " + DIAS_MAXIMOS_SOMADOS + " dias");
        }
        if (diasJaTrancados + dias > DIAS_MAXIMOS_SOMADOS) {
            throw new MatriculaRecusadaException("Trancamento recusado", "A matrícula já tem %d dias trancados;"
                    .formatted(diasJaTrancados) + " restam %d dos %d permitidos".formatted(
                    DIAS_MAXIMOS_SOMADOS - diasJaTrancados, DIAS_MAXIMOS_SOMADOS));
        }
        return new Periodo(comeco, (int) dias);
    }
}
