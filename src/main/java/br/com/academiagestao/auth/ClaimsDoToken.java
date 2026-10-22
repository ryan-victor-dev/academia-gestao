package br.com.academiagestao.auth;

/** Nomes das claims próprias do sistema no JWT. Público para outros módulos lerem o token. */
public final class ClaimsDoToken {

    /** Perfil do usuário (ADMIN, RECEPCAO, INSTRUTOR, ALUNO). */
    public static final String PERFIL = "perfil";

    /** Id do cadastro do aluno; só existe em tokens de perfil ALUNO. */
    public static final String ALUNO_ID = "alunoId";

    /** Versão dos tokens do usuário no momento do login; conferida a cada requisição. */
    public static final String VERSAO_TOKEN = "versaoToken";

    /** Presente (true) quando o usuário entrou com senha temporária e precisa trocá-la. */
    public static final String TROCAR_SENHA = "trocarSenha";

    /** Presente (true) no login de aluno inativo: só consulta (ADR 0029). */
    public static final String SOMENTE_LEITURA = "somenteLeitura";

    private ClaimsDoToken() {
    }
}
