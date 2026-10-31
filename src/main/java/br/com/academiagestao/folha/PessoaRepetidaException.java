package br.com.academiagestao.folha;

/** A pessoa já está na folha (o login ou o CPF): 409 com o motivo. */
class PessoaRepetidaException extends RuntimeException {

    PessoaRepetidaException(String mensagem) {
        super(mensagem);
    }
}
