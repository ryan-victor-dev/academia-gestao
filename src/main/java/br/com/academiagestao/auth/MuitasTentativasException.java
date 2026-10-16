package br.com.academiagestao.auth;

import java.time.Duration;

class MuitasTentativasException extends RuntimeException {

    private final Duration esperar;

    MuitasTentativasException(Duration esperar) {
        super("Muitas tentativas de login. Tente de novo mais tarde.");
        this.esperar = esperar;
    }

    Duration esperar() {
        return esperar;
    }
}
