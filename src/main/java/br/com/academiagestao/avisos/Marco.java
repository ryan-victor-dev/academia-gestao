package br.com.academiagestao.avisos;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

/**
 * Os dias em que a fatura é lembrada (ADR 0047, decisão 7), contados do prazo: 5, 3 e 1 dia antes, no dia, e com 1, 3
 * e 5 dias de atraso. Só no dia exato: um dia em que o servidor ficou desligado o dia todo não é recuperado depois
 * (o aviso seguinte vem no próximo marco). Java puro.
 */
enum Marco {
    VENCE_EM_5(-5), VENCE_EM_3(-3), VENCE_EM_1(-1), VENCE_HOJE(0), ATRASADA_1(1), ATRASADA_3(3), ATRASADA_5(5);

    /** Dias depois do prazo (negativo: antes). */
    private final int diasDepoisDoPrazo;

    Marco(int diasDepoisDoPrazo) {
        this.diasDepoisDoPrazo = diasDepoisDoPrazo;
    }

    int diasDepoisDoPrazo() {
        return diasDepoisDoPrazo;
    }

    boolean atrasada() {
        return diasDepoisDoPrazo > 0;
    }

    /** O prazo das faturas que caem neste marco hoje. */
    LocalDate prazoPara(LocalDate hoje) {
        return hoje.minusDays(diasDepoisDoPrazo);
    }

    /** O marco de uma fatura com este prazo, hoje (se cair em algum). */
    static Optional<Marco> de(LocalDate prazo, LocalDate hoje) {
        return Arrays.stream(values()).filter(m -> m.prazoPara(hoje).equals(prazo)).findFirst();
    }
}
