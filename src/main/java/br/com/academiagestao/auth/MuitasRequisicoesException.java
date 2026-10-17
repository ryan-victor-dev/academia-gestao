package br.com.academiagestao.auth;

import java.time.Duration;

class MuitasRequisicoesException extends RuntimeException {

    private final Duration esperar;

    MuitasRequisicoesException(Duration esperar) {
        super("Muitas requisições em pouco tempo. Tente de novo em alguns segundos.");
        this.esperar = esperar;
    }

    Duration esperar() {
        return esperar;
    }
}
