package br.com.academiagestao.compartilhado;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** A idade em anos completos (ADR 0048), como nas telas (formato.ts). */
class NascimentoTest {

    @Test
    void idadeMudaNoDiaDoAniversario() {
        LocalDate nascimento = LocalDate.of(1990, 5, 10);
        assertThat(Nascimento.idade(nascimento, LocalDate.of(2026, 5, 9))).isEqualTo(35);
        assertThat(Nascimento.idade(nascimento, LocalDate.of(2026, 5, 10))).isEqualTo(36);
        assertThat(Nascimento.idade(nascimento, LocalDate.of(2026, 4, 30))).isEqualTo(35);
        assertThat(Nascimento.idade(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30))).isZero();
    }

    @Test
    void quemNasceuEm29DeFevereiroCompletaEm1DeMarcoNosAnosComuns() {
        LocalDate nascimento = LocalDate.of(2000, 2, 29);
        assertThat(Nascimento.idade(nascimento, LocalDate.of(2025, 2, 28))).isEqualTo(24);
        assertThat(Nascimento.idade(nascimento, LocalDate.of(2025, 3, 1))).isEqualTo(25);
        assertThat(Nascimento.idade(nascimento, LocalDate.of(2028, 2, 29))).isEqualTo(28);
    }
}
