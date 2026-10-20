package br.com.academiagestao.cobranca;

import java.util.UUID;

class CobrancaNaoEncontradaException extends RuntimeException {

    CobrancaNaoEncontradaException(UUID id) {
        super("Cobrança " + id + " não encontrada");
    }
}
