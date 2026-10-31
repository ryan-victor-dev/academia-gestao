package br.com.academiagestao.compartilhado;

import java.time.Duration;
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

    enum Tipo { CONSULTA_ALUNO, LOGIN, LOGIN_FALHO, LOGIN_BLOQUEADO, TROCA_SENHA_FALHA, RELATORIO_PERFIL, CONSULTA_FOLHA }

    /**
     * Os que o {@link #login} grava. A consulta e o bloqueio têm método próprio e ficam de fora:
     * quem tentar passá-los aqui não compila (pendências baixas, item 25).
     */
    enum TipoDeLogin {
        LOGIN(Tipo.LOGIN), LOGIN_FALHO(Tipo.LOGIN_FALHO), TROCA_SENHA_FALHA(Tipo.TROCA_SENHA_FALHA);

        private final Tipo tipo;

        TipoDeLogin(Tipo tipo) {
            this.tipo = tipo;
        }

        /** Como fica no registro (e na consulta). */
        public Tipo tipo() {
            return tipo;
        }
    }

    /** Alguém da equipe abriu o cadastro do aluno. */
    void consultaDeAluno(UUID usuarioId, UUID alunoId, String ip);

    /**
     * O ADMIN abriu o relatório de idades e gênero (ADR 0048), com a orientação sexual de todos: uma linha por
     * abertura, e não uma por pessoa listada.
     */
    void relatorioDePerfil(UUID usuarioId, String ip);

    /** O ADMIN consultou a folha (ADR 0049), com o valor combinado com cada pessoa: uma linha por consulta. */
    void consultaDaFolha(UUID usuarioId, String ip);

    /**
     * Um login ou uma troca de senha recusada. {@code usuarioId} nulo: a conta digitada não
     * existe. {@code conta}: o e-mail digitado, normalizado. Nunca a senha. O bloqueio vai por
     * {@link #loginBloqueado}.
     */
    void login(TipoDeLogin tipo, UUID usuarioId, String conta, String ip);

    /**
     * Uma tentativa recusada pelo limite (login ou troca de senha). Só a primeira de cada conta
     * e IP dentro da {@code janela} é gravada: um ataque de horas vira poucas linhas, e não uma
     * por tentativa (diagnóstico da etapa 8, M3). Tentativas simultâneas podem gravar uma ou
     * outra a mais; o número não cresce com a duração do ataque.
     */
    void loginBloqueado(UUID usuarioId, String conta, String ip, Duration janela);
}
