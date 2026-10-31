package br.com.academiagestao.compartilhado;

import java.time.LocalDate;
import java.time.Period;

/**
 * A data de nascimento de alunos e da equipe (ADR 0048): a mesma regra para os dois. Java puro (ADR 0004).
 * O "hoje" vem de quem chama, no fuso da academia ({@link Calendario#hoje}).
 */
public final class Nascimento {

    /** Nascimento mais antigo aceito (decisão do dono, revisão de 2026-10-17): pega erro de digitação. */
    public static final int IDADE_MAXIMA_ANOS = 120;

    private Nascimento() {
    }

    /** Nem no futuro, nem mais de 120 anos atrás. */
    public static void conferir(LocalDate data, LocalDate hoje) {
        if (data.isAfter(hoje)) {
            throw new DadosInvalidosException("data de nascimento não pode estar no futuro");
        }
        if (data.isBefore(hoje.minusYears(IDADE_MAXIMA_ANOS))) {
            throw new DadosInvalidosException("data de nascimento pode ser de no máximo " + IDADE_MAXIMA_ANOS
                    + " anos atrás");
        }
    }

    /**
     * Os anos completos. Quem nasceu em 29/02 completa os anos em 01/03 nos anos comuns (Código Civil, art. 132,
     * § 3º), como nas telas.
     */
    public static int idade(LocalDate data, LocalDate hoje) {
        return Period.between(data, hoje).getYears();
    }
}
