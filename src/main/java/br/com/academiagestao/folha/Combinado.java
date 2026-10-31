package br.com.academiagestao.folha;

import br.com.academiagestao.compartilhado.DadosInvalidosException;

import java.math.BigDecimal;

/**
 * O combinado com a pessoa (ADR 0049): o vínculo e, se houver, o valor fixo por mês. Sem valor fixo (quem recebe
 * por aula, por exemplo), o valor fica nulo. Serve só para conferir o mês: o pago é sempre digitado. Java puro.
 */
record Combinado(Vinculo vinculo, BigDecimal valor) {

    static final BigDecimal VALOR_MAXIMO = new BigDecimal("999999.99");

    Combinado {
        if (vinculo == null) {
            throw new DadosInvalidosException("vinculo: é obrigatório (CLT, MEI ou AUTONOMO)");
        }
        if (valor != null) {
            if (valor.signum() <= 0) {
                throw new DadosInvalidosException("valorCombinado: precisa ser maior que zero");
            }
            if (valor.stripTrailingZeros().scale() > 2) {
                throw new DadosInvalidosException("valorCombinado: até 2 casas decimais");
            }
            if (valor.compareTo(VALOR_MAXIMO) > 0) {
                throw new DadosInvalidosException("valorCombinado: pode ser no máximo " + VALOR_MAXIMO);
            }
            valor = valor.setScale(2);
        }
    }
}
