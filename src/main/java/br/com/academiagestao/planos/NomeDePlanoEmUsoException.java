package br.com.academiagestao.planos;

class NomeDePlanoEmUsoException extends RuntimeException {

    NomeDePlanoEmUsoException() {
        super("Já existe um plano ativo com este nome");
    }
}
