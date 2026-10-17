package br.com.academiagestao.matriculas;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * O que a matrícula copia do plano no dia em que é feita. É só um retrato: reajustar o
 * plano depois não muda o que já foi contratado (ADR 0013 e 0016).
 */
record PlanoContratado(UUID planoId, String nome, BigDecimal valorMensal, int duracaoMeses) {
}
