package br.com.academiagestao.cobranca;

import java.time.LocalDate;

class CaixaNaoEncontradoException extends RuntimeException {

    CaixaNaoEncontradoException(LocalDate dia) {
        super("O caixa de " + dia + " não foi aberto");
    }
}
