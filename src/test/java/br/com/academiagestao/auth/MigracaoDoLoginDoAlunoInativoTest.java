package br.com.academiagestao.auth;

import br.com.academiagestao.alunos.CpfsParaTeste;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A V34 devolve o login aos alunos já inativos, só de leitura, e derruba os tokens antigos
 * (ADR 0029). Banco próprio, com as migrações até a V33, os dados, e então a V34.
 */
class MigracaoDoLoginDoAlunoInativoTest {

    @Test
    void loginDesligadoPelaInativacaoVoltaSoDeLeitura() {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            var banco = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var flyway = Flyway.configure().dataSource(banco).locations("classpath:db/migration");
            flyway.target("33").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(banco);

            UUID inativo = login(jdbc, aluno(jdbc, false), false, "ALUNO");
            UUID ativo = login(jdbc, aluno(jdbc, true), true, "ALUNO");
            UUID equipeDesligada = login(jdbc, null, false, "RECEPCAO");

            flyway.target("34").load().migrate();

            assertThat(estado(jdbc, inativo)).containsEntry("ativo", true).containsEntry("somente_leitura", true)
                    .containsEntry("versao_token", 4);
            assertThat(estado(jdbc, ativo)).containsEntry("ativo", true).containsEntry("somente_leitura", false)
                    .containsEntry("versao_token", 3);
            assertThat(estado(jdbc, equipeDesligada)).as("só login de aluno")
                    .containsEntry("ativo", false).containsEntry("somente_leitura", false);
            assertThatThrownBy(() -> jdbc.update("UPDATE usuarios SET somente_leitura = true WHERE id = ?", equipeDesligada))
                    .hasMessageContaining("ck_usuarios_somente_leitura_so_aluno");
        }
    }

    private static UUID aluno(JdbcTemplate jdbc, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, ativo) VALUES (?, 'Aluno', ?, ?)", id, CpfsParaTeste.aleatorio(),
                ativo);
        return id;
    }

    private static UUID login(JdbcTemplate jdbc, UUID aluno, boolean ativo, String perfil) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usuarios (id, nome, email, senha_hash, perfil, aluno_id, ativo, versao_token)
                VALUES (?, 'U', ?, 'x', ?, ?, ?, 3)""", id, id + "@a.com", perfil, aluno, ativo);
        return id;
    }

    private static Map<String, Object> estado(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForMap("SELECT ativo, somente_leitura, versao_token FROM usuarios WHERE id = ?", id);
    }
}
