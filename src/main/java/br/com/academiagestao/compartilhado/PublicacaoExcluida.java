package br.com.academiagestao.compartilhado;

import java.util.UUID;

/** A publicação saiu (ADR 0047): os avisos dela que ainda não foram enviados são cancelados. */
public record PublicacaoExcluida(UUID publicacaoId) {
}
