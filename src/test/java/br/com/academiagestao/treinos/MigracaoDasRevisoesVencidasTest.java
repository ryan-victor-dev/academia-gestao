package br.com.academiagestao.treinos;

import br.com.academiagestao.alunos.CpfsParaTeste;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A V33 marca as fichas atuais de quem já estava inativo antes dela (diagnóstico da etapa 7,
 * T3): sem isso, eles voltariam à lista de revisões vencidas. Banco próprio, com as migrações
 * até a V32, os dados, e então a V33.
 */
class MigracaoDasRevisoesVencidasTest {

    @Test
    void fichaAtualDeAlunoJaInativoSaiDaLista() {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            var banco = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var flyway = Flyway.configure().dataSource(banco).locations("classpath:db/migration");
            flyway.target("32").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(banco);

            UUID usuario = UUID.randomUUID();
            jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'I', 'i@a.com', 'x', 'INSTRUTOR')",
                    usuario);
            UUID inativo = aluno(jdbc, false);
            UUID atualDoInativo = ficha(jdbc, inativo, usuario, false);
            UUID encerradaDoInativo = ficha(jdbc, inativo, usuario, true);
            UUID ativo = aluno(jdbc, true);
            UUID atualDoAtivo = ficha(jdbc, ativo, usuario, false);

            flyway.target("33").load().migrate();

            assertThat(alunoAtivo(jdbc, atualDoInativo)).isFalse();
            assertThat(alunoAtivo(jdbc, atualDoAtivo)).isTrue();
            assertThat(alunoAtivo(jdbc, encerradaDoInativo)).as("a encerrada não muda").isTrue();
        }
    }

    private static UUID aluno(JdbcTemplate jdbc, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, ativo) VALUES (?, 'Aluno', ?, ?)", id, CpfsParaTeste.aleatorio(),
                ativo);
        return id;
    }

    private static UUID ficha(JdbcTemplate jdbc, UUID aluno, UUID usuario, boolean encerrada) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fichas (id, aluno_id, montada_em, montada_por, encerrada_em, revisao_em)
                VALUES (?, ?, now() - interval '1 day', ?, CASE WHEN ? THEN now() END, current_date - 1)""",
                id, aluno, usuario, encerrada);
        return id;
    }

    private static boolean alunoAtivo(JdbcTemplate jdbc, UUID ficha) {
        return jdbc.queryForObject("SELECT aluno_ativo FROM fichas WHERE id = ?", Boolean.class, ficha);
    }
}
