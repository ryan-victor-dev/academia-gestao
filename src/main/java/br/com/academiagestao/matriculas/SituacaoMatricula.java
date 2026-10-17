package br.com.academiagestao.matriculas;

/** Calculada pelas datas no dia da consulta; só CANCELADA é gravada (ADRs 0016 e 0018). */
public enum SituacaoMatricula {
    AGENDADA,
    VIGENTE,
    /** Vigente, mas com o aluno parado por um trancamento. */
    TRANCADA,
    ENCERRADA,
    CANCELADA
}
