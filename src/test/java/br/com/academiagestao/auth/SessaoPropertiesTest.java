package br.com.academiagestao.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Prazos decididos pelo dono (ADR 0033): um turno para a equipe, 30 dias para o aluno. */
class SessaoPropertiesTest {

    private final SessaoProperties prazos = new SessaoProperties(Duration.ofHours(12), Duration.ofDays(30),
            Duration.ofSeconds(30));

    @Test
    void alunoTemOPrazoDeleEAEquipeOTurno() {
        assertThat(prazos.duracaoPara(Perfil.ALUNO)).isEqualTo(Duration.ofDays(30));
        for (Perfil perfil : new Perfil[] {Perfil.ADMIN, Perfil.RECEPCAO, Perfil.INSTRUTOR}) {
            assertThat(prazos.duracaoPara(perfil)).as(perfil.name()).isEqualTo(Duration.ofHours(12));
        }
    }
}
