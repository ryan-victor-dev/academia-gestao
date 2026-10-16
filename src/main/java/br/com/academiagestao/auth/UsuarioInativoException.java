package br.com.academiagestao.auth;

class UsuarioInativoException extends RuntimeException {

    UsuarioInativoException() {
        super("O usuário está inativo; reative-o antes de gerar uma nova senha");
    }
}
