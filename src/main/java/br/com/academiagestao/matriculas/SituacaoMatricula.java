package br.com.academiagestao.matriculas;

/** Calculada pelas datas no dia da consulta; só CANCELADA é gravada (ADR 0016). */
public enum SituacaoMatricula {
    AGENDADA,
    VIGENTE,
    ENCERRADA,
    CANCELADA
}
