package br.com.academiagestao.matriculas;

import br.com.academiagestao.planos.FormaPagamento;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * O que a matrícula copia do plano no dia em que é feita. É só um retrato: mudar o plano
 * depois não muda o que já foi contratado (ADRs 0013, 0016 e 0018).
 */
record PlanoContratado(UUID planoId, String nome, BigDecimal valorMensal, int duracaoMeses, boolean fidelidade,
                       BigDecimal percentualMulta, FormaPagamento formaPagamento) {

    /** Sem fidelidade e com pagamento mensal. */
    PlanoContratado(UUID planoId, String nome, BigDecimal valorMensal, int duracaoMeses) {
        this(planoId, nome, valorMensal, duracaoMeses, false, null, FormaPagamento.MENSAL);
    }
}
