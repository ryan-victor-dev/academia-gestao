package br.com.academiagestao.pagamentos;

/** Dado inválido num pedido de pagamento online ou na configuração (400). */
class PagamentoOnlineInvalidoException extends RuntimeException {

    PagamentoOnlineInvalidoException(String mensagem) {
        super(mensagem);
    }
}
