package br.com.academiagestao.folha;

class PessoaNaoEncontradaException extends RuntimeException {

    PessoaNaoEncontradaException(String mensagem) {
        super(mensagem);
    }
}
