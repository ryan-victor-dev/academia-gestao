package br.com.academiagestao.pagamentos;

/** O pedido é válido, mas não pode ser feito agora: forma desligada, cobrança fora das pagáveis (409). */
class PagamentoOnlineRecusadoException extends RuntimeException {

    PagamentoOnlineRecusadoException(String mensagem) {
        super(mensagem);
    }
}
