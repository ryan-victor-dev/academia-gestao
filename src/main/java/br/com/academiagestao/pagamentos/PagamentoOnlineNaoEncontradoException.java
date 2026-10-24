package br.com.academiagestao.pagamentos;

/** Cobrança, aluno ou pagamento online que não existe, ou não é de quem pediu (404). */
class PagamentoOnlineNaoEncontradoException extends RuntimeException {

    PagamentoOnlineNaoEncontradoException(String mensagem) {
        super(mensagem);
    }
}
