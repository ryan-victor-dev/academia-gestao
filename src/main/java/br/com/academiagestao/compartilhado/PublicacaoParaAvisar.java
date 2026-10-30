package br.com.academiagestao.compartilhado;

import java.time.Instant;
import java.util.UUID;

/**
 * Um evento ou comunicado foi publicado com "avisar" (ADR 0047, decisão 8). O módulo de notícias solta, e o de avisos
 * ouve depois do commit e põe na fila os e-mails e SMS: notícias não depende de avisos (sem ciclo). Leva o que foi
 * publicado (o aviso sai com o texto daquele momento).
 */
public record PublicacaoParaAvisar(UUID publicacaoId, String tipo, String titulo, String texto, Instant eventoInicio,
                                   Instant eventoFim, String eventoLocal) {
}
