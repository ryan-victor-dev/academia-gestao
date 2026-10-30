package br.com.academiagestao.noticias;

class PublicacaoNaoEncontradaException extends RuntimeException {

    PublicacaoNaoEncontradaException() {
        super("Publicação não encontrada");
    }
}
