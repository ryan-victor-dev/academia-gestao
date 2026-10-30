package br.com.academiagestao.noticias;

import org.springframework.http.HttpStatus;

/** Anexo que não entra (ADR 0047): tipo não aceito, grande demais, publicação cheia ou espaço esgotado. */
class AnexoRecusadoException extends RuntimeException {

    private final HttpStatus status;
    private final String titulo;

    AnexoRecusadoException(HttpStatus status, String titulo, String detalhe) {
        super(detalhe);
        this.status = status;
        this.titulo = titulo;
    }

    HttpStatus status() {
        return status;
    }

    String titulo() {
        return titulo;
    }
}
