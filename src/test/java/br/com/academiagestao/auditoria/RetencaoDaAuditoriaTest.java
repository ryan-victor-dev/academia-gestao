package br.com.academiagestao.auditoria;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retenção de 5 anos da trilha (diagnóstico de 2026-10-18, decisão do dono). A aplicação
 * conecta como academia_app, que não tem DELETE na trilha: só a função do dono apaga.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RetencaoDaAuditoriaTest {

    // Marca as linhas deste teste: os outros dividem o mesmo banco.
    private static final String ACAO = "TESTE_RETENCAO";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RetencaoDaAuditoria retencao;

    @Autowired
    PostgreSQLContainer postgres;

    // Os acessos deste teste: um IP que nenhum outro usa.
    private static final String IP = "203.0.113.77";

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM auditoria WHERE acao = ?", ACAO);
        jdbc.update("DELETE FROM acessos WHERE ip = ?", IP);
    }

    /** Grava, como o dono do banco, acessos com a idade dada; a conta guarda a marca. */
    private void gravarAcessosComIdade(String idade, String marca, int quantos) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test");
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO acessos (momento, tipo, conta, ip) SELECT now() - interval '" + idade
                    + "', 'LOGIN_FALHO', '" + marca + "', '" + IP + "' FROM generate_series(1, " + quantos + ")");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> acessosQueSobraram() {
        return jdbc.queryForList("SELECT DISTINCT conta FROM acessos WHERE ip = ? ORDER BY 1", String.class, IP);
    }

    /** Grava, como o dono do banco, registros com a idade dada (ex.: '5 years 1 day'). */
    private void gravarComIdade(String idade, String marca, int quantos) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test");
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO auditoria (momento, entidade, entidade_id, acao, alteracoes) "
                    + "SELECT now() - interval '" + idade + "', 'PLANO', gen_random_uuid(), '" + ACAO + "', "
                    + "jsonb_build_object('marca', '" + marca + "') FROM generate_series(1, " + quantos + ")");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> marcasQueSobraram() {
        return jdbc.queryForList("SELECT DISTINCT alteracoes->>'marca' FROM auditoria WHERE acao = ? ORDER BY 1",
                String.class, ACAO);
    }

    @Test
    void apagaSoOQueTemMaisDeCincoAnos() {
        gravarComIdade("5 years 1 day", "vencido", 1);
        gravarComIdade("5 years -1 day", "quase", 1);
        gravarComIdade("1 day", "recente", 1);

        retencao.apagarVencidos();

        assertThat(marcasQueSobraram()).containsExactly("quase", "recente");
    }

    @Test
    void apagaEmLotesAteAcabar() {
        gravarComIdade("6 years", "vencido", 25);
        gravarComIdade("1 day", "recente", 1);

        new RetencaoDaAuditoria(jdbc, 10).apagarVencidos();

        assertThat(marcasQueSobraram()).containsExactly("recente");
    }

    @Test
    void ninguemAlemDaAplicacaoExecutaAFuncao() {
        // Uma entrada "=X/" na lista de permissões seria o EXECUTE para todos (PUBLIC).
        String permissoes = jdbc.queryForObject(
                "SELECT proacl::text FROM pg_proc WHERE proname = 'apagar_auditoria_vencida'", String.class);
        assertThat(permissoes).doesNotContain("{=X", ",=X").contains("academia_app=X");
    }

    @Test
    void cadaChamadaApagaNoMaximoDezMil() throws SQLException {
        gravarComIdade("6 years", "vencido", 10_001);
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "academia_app",
                "senha-do-app-so-para-testes");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT apagar_auditoria_vencida(1000000)")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(10_000);
        }
        assertThat(marcasQueSobraram()).containsExactly("vencido");
    }

    /** Consultas e logins ficam 1 ano (decisão do dono, ADR 0031); a trilha continua 5. */
    @Test
    void acessosFicamUmAno() {
        gravarAcessosComIdade("1 year 1 day", "vencido", 1);
        gravarAcessosComIdade("1 year -1 day", "quase", 1);
        gravarAcessosComIdade("1 day", "recente", 1);
        gravarComIdade("2 years", "alteracao", 1);

        retencao.apagarVencidos();

        assertThat(acessosQueSobraram()).containsExactly("quase", "recente");
        assertThat(marcasQueSobraram()).containsExactly("alteracao");
    }

    @Test
    void acessosEmLotesAteAcabar() {
        gravarAcessosComIdade("2 years", "vencido", 25);
        gravarAcessosComIdade("1 day", "recente", 1);

        new RetencaoDaAuditoria(jdbc, 10).apagarVencidos();

        assertThat(acessosQueSobraram()).containsExactly("recente");
    }

    @Test
    void ninguemAlemDaAplicacaoApagaAcessos() throws SQLException {
        String permissoes = jdbc.queryForObject(
                "SELECT proacl::text FROM pg_proc WHERE proname = 'apagar_acessos_vencidos'", String.class);
        assertThat(permissoes).doesNotContain("{=X", ",=X").contains("academia_app=X");
        gravarAcessosComIdade("2 years", "vencido", 10_001);
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "academia_app",
                "senha-do-app-so-para-testes");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT apagar_acessos_vencidos(1000000)")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(10_000);
        }
        assertThat(acessosQueSobraram()).containsExactly("vencido");
    }
}
