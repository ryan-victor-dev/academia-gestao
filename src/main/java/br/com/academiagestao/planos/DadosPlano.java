package br.com.academiagestao.planos;

import java.math.BigDecimal;

/**
 * Dados editáveis de um plano, usados no cadastro e na edição. Fidelidade e forma de
 * pagamento ausentes valem "sem fidelidade" e "mensal" (ADR 0018).
 */
public record DadosPlano(String nome, BigDecimal valorMensal, Integer duracaoMeses, Boolean fidelidade,
                         BigDecimal percentualMulta, FormaPagamento formaPagamento) {

    /** Plano sem fidelidade e com pagamento mensal. */
    public DadosPlano(String nome, BigDecimal valorMensal, Integer duracaoMeses) {
        this(nome, valorMensal, duracaoMeses, null, null, null);
    }
}
