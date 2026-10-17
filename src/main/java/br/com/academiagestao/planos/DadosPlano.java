package br.com.academiagestao.planos;

import java.math.BigDecimal;

/** Dados editáveis de um plano, usados no cadastro e na edição. */
public record DadosPlano(String nome, BigDecimal valorMensal, Integer duracaoMeses) {
}
