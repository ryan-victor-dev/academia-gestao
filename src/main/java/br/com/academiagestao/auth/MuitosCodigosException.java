package br.com.academiagestao.auth;

import java.time.Duration;

/** Pediu códigos demais em pouco tempo (ADR 0038): espere e peça de novo. */
class MuitosCodigosException extends RuntimeException {

    private final Duration esperar;

    MuitosCodigosException(Duration esperar) {
        super("Muitos códigos pedidos. Espere um pouco e peça de novo.");
        this.esperar = esperar;
    }

    Duration esperar() {
        return esperar;
    }
}
