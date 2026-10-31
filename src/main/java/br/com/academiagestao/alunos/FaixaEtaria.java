package br.com.academiagestao.alunos;

/** As faixas do relatório de idades e gênero (ADR 0048), pelos anos completos. Java puro. */
enum FaixaEtaria {
    ATE_17, DE_18_A_29, DE_30_A_39, DE_40_A_49, DE_50_A_59, A_PARTIR_DE_60, SEM_DATA;

    /** {@code idade} nula: a pessoa é de antes da data obrigatória e ainda não a informou. */
    static FaixaEtaria de(Integer idade) {
        if (idade == null) {
            return SEM_DATA;
        }
        if (idade <= 17) {
            return ATE_17;
        }
        if (idade <= 29) {
            return DE_18_A_29;
        }
        if (idade <= 39) {
            return DE_30_A_39;
        }
        if (idade <= 49) {
            return DE_40_A_49;
        }
        return idade <= 59 ? DE_50_A_59 : A_PARTIR_DE_60;
    }
}
