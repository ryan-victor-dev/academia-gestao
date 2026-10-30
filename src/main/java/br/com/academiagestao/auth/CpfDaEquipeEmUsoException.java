package br.com.academiagestao.auth;

/** O CPF já está no login de outra pessoa da equipe (ADR 0047). */
class CpfDaEquipeEmUsoException extends RuntimeException {

    CpfDaEquipeEmUsoException() {
        super("Este CPF já é de outro login da equipe");
    }
}
