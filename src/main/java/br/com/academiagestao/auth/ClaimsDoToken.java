package br.com.academiagestao.auth;

/** Nomes das claims próprias do sistema no JWT. Público para outros módulos lerem o token. */
public final class ClaimsDoToken {

    /** Perfil do usuário (ADMIN, RECEPCAO, INSTRUTOR, ALUNO). */
    public static final String PERFIL = "perfil";

    /** Id do cadastro do aluno; só existe em tokens de perfil ALUNO. */
    public static final String ALUNO_ID = "alunoId";

    private ClaimsDoToken() {
    }
}
