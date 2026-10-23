package br.com.academiagestao.compartilhado;

import java.util.UUID;

/**
 * Registro de acessos (ADR 0031): quem da equipe abriu o cadastro de qual aluno, e os logins
 * (entrada, senha errada, bloqueio) com a conta e o IP. Fica 1 ano. Buscas e listas não
 * entram, para não gerar uma linha por aluno listado.
 *
 * Grava fora de qualquer transação: o login com senha errada é desfeito, e a consulta é só
 * de leitura. Chamar de dentro de uma transação é erro (a implementação recusa).
 */
public interface RegistroDeAcessos {

    enum Tipo { CONSULTA_ALUNO, LOGIN, LOGIN_FALHO, LOGIN_BLOQUEADO, TROCA_SENHA_FALHA }

    /** Alguém da equipe abriu o cadastro do aluno. */
    void consultaDeAluno(UUID usuarioId, UUID alunoId, String ip);

    /**
     * Um login ou uma troca de senha. {@code usuarioId} nulo: a conta digitada não existe.
     * {@code conta}: o e-mail digitado, normalizado. Nunca a senha.
     */
    void login(Tipo tipo, UUID usuarioId, String conta, String ip);
}
