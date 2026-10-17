package br.com.academiagestao.matriculas;

/** Calculada pelas datas no dia da consulta; só CANCELADA e TROCADA são gravadas (ADRs 0016 e 0018). */
public enum SituacaoMatricula {
    AGENDADA,
    VIGENTE,
    /** Vigente, mas com o aluno parado por um trancamento. */
    TRANCADA,
    ENCERRADA,
    CANCELADA,
    /** Encerrada na véspera de uma troca de plano; a matrícula nova aponta para ela. */
    TROCADA
}
