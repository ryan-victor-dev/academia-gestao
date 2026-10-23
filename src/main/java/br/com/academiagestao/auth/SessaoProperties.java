package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Prazos da sessão renovável (ADR 0033), contados do login; renovar não estica.
 *
 * @param duracaoEquipe ADMIN, RECEPCAO e INSTRUTOR: um turno
 * @param duracaoAluno  ALUNO, no próprio celular
 * @param folga         por quanto tempo o segredo anterior ainda vale depois de trocado (duas
 *                      abas renovando juntas mandam o mesmo segredo)
 */
@ConfigurationProperties(prefix = "academia.sessao")
record SessaoProperties(
        @DefaultValue("12h") Duration duracaoEquipe,
        @DefaultValue("30d") Duration duracaoAluno,
        @DefaultValue("30s") Duration folga) {

    Duration duracaoPara(Perfil perfil) {
        return perfil == Perfil.ALUNO ? duracaoAluno : duracaoEquipe;
    }
}
