package br.com.academiagestao.matriculas;

import br.com.academiagestao.planos.FormaPagamento;

/**
 * Como o aluno paga esta matrícula (ADR 0018), copiado do plano no dia em que foi feita. É
 * da matrícula, e não do plano (R2): a entidade guarda as regras do próprio módulo. Os nomes
 * são os mesmos do plano, no banco e na API.
 */
enum FormaDePagamento {
    /** Uma mensalidade por mês do período, no dia do início da matrícula. */
    MENSAL,
    /** Uma cobrança com o valor total, no início. */
    A_VISTA;

    /**
     * Sem default: uma forma nova no plano não compila aqui até alguém decidir o que ela é. O
     * plano sempre tem forma (NOT NULL, V13).
     */
    static FormaDePagamento doPlano(FormaPagamento forma) {
        return switch (forma) {
            case MENSAL -> MENSAL;
            case A_VISTA -> A_VISTA;
        };
    }
}
