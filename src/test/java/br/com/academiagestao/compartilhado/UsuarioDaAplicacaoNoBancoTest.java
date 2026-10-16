package br.com.academiagestao.compartilhado;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conecta como o usuário da aplicação e tenta o que alguém faria com uma injeção de SQL.
 * Ler e gravar dados funciona; mexer na estrutura do banco ou nos usuários, não.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UsuarioDaAplicacaoNoBancoTest {

    @Autowired
    PostgreSQLContainer postgres;

    private Connection comoAplicacao() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "academia_app", "senha-do-app-so-para-testes");
    }

    private void executar(String sql) throws SQLException {
        try (Connection c = comoAplicacao(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void naoEhSuperusuarioNemCriaBancoOuUsuario() throws SQLException {
        try (Connection c = comoAplicacao(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user");
            rs.next();
            assertThat(rs.getBoolean(1)).as("superusuário").isFalse();
            assertThat(rs.getBoolean(2)).as("cria banco").isFalse();
            assertThat(rs.getBoolean(3)).as("cria usuário").isFalse();
        }
    }

    @Test
    void leEGravaDados() {
        assertThatCode(() -> {
            executar("SELECT count(*) FROM usuarios");
            executar("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) "
                    + "VALUES (gen_random_uuid(), 'Teste', 100, 1)");
            executar("UPDATE planos SET nome = 'Teste 2' WHERE nome = 'Teste'");
            executar("DELETE FROM planos WHERE nome = 'Teste 2'");
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE alunos",
            "TRUNCATE usuarios",
            "ALTER TABLE usuarios ADD COLUMN x INT",
            "CREATE TABLE invasor (x INT)",
            "CREATE ROLE invasor LOGIN SUPERUSER PASSWORD 'x'",
            "ALTER ROLE academia_app SUPERUSER",
            "ALTER ROLE academia SUPERUSER",
            "CREATE EXTENSION IF NOT EXISTS pgcrypto",
            "SELECT * FROM flyway_schema_history",
            "DELETE FROM flyway_schema_history",
            "COPY usuarios TO '/tmp/usuarios.csv'",
            "SELECT pg_read_file('/etc/passwd')",
    })
    void naoMexeNaEstruturaNemNoServidor(String ataque) {
        assertThatThrownBy(() -> executar(ataque)).isInstanceOf(SQLException.class);
    }
}
