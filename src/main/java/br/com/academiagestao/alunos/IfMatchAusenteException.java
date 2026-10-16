package br.com.academiagestao.alunos;

class IfMatchAusenteException extends RuntimeException {

    IfMatchAusenteException() {
        super("Envie o cabeçalho If-Match com o ETag recebido no GET do aluno.");
    }
}
