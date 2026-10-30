package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.MeioDePagamento;

/**
 * O mesmo comprovante já pagou outra cobrança (ADR 0046, item 10): o Pix, em qualquer dia; o
 * cartão, na mesma forma e no mesmo dia. 409.
 */
class CodigoDaTransacaoEmUsoException extends RuntimeException {

    CodigoDaTransacaoEmUsoException(MeioDePagamento meio) {
        super(meio == MeioDePagamento.PIX
                ? "Este código de Pix já pagou outra cobrança: confira o comprovante"
                : "Este código de cartão já pagou outra cobrança hoje, na mesma forma: confira o comprovante");
    }
}
