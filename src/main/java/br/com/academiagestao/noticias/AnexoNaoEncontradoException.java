package br.com.academiagestao.noticias;

class AnexoNaoEncontradoException extends RuntimeException {

    AnexoNaoEncontradoException() {
        super("Anexo não encontrado");
    }
}
