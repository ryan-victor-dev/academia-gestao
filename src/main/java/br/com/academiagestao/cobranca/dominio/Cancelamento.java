package br.com.academiagestao.cobranca.dominio;

import java.time.Instant;
import java.util.UUID;

/** Quem cancelou ({@code null} = o sistema, ao encerrar a matrícula), quando e por quê. */
public record Cancelamento(Instant em, UUID por, String motivo) {
}
