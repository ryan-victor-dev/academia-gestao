package br.com.academiagestao.planos;

import java.math.BigDecimal;
import java.util.UUID;

/** Condições de um plano no momento da consulta, para outro módulo copiar (ex.: matrícula). */
public record PlanoOfertado(UUID id, BigDecimal valorMensal, int duracaoMeses, boolean ativo) {
}
