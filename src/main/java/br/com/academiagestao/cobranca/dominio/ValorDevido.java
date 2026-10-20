package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;

/** O que se paga num dia: o valor da cobrança mais, se atrasada, o acréscimo e os juros. */
public record ValorDevido(BigDecimal valor, BigDecimal acrescimo, BigDecimal juros, long diasDeAtraso) {

    public BigDecimal total() {
        return valor.add(acrescimo).add(juros);
    }
}
