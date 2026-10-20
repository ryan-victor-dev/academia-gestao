package br.com.academiagestao.cobranca.dominio;

public enum TipoCobranca {
    /** Uma por mês do período (pagamento mensal). */
    MENSALIDADE,
    /** O período inteiro, no início (pagamento à vista). */
    A_VISTA,
    /** Multa por sair antes do fim de uma matrícula com fidelidade (ADR 0018). */
    MULTA
}
