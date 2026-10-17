package br.com.academiagestao.planos;

/** Como o aluno paga o plano (ADR 0018). A matrícula copia a forma do dia em que foi feita. */
public enum FormaPagamento {
    /** Uma mensalidade por mês do período, no dia do início da matrícula. */
    MENSAL,
    /** Uma cobrança com o valor total, no início. */
    A_VISTA
}
