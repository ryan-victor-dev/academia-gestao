package br.com.academiagestao.compartilhado;

import java.io.IOException;

/** O corpo passou do limite enquanto era lido (corpo enviado em partes, sem Content-Length). */
class CorpoGrandeDemaisException extends IOException {

    CorpoGrandeDemaisException(long limite) {
        super("O corpo da requisição pode ter no máximo " + limite / 1024 + " KB.");
    }
}
