package br.com.academiagestao.treinos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O que o banco garante das fichas, mesmo que um erro no código (ou uma injeção de SQL) tente
 * o contrário (V30 e V32): uma atual por aluno, e a encerrada não muda nem perde nada.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FichaNoBancoTest {

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transacao;

    @Autowired
    PostgreSQLContainer postgres;

    private UUID aluno;
    private UUID usuario;
    private UUID exercicio;

    @BeforeEach
    void preparar() {
        aluno = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        usuario = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'I', ?, 'x', 'INSTRUTOR')",
                usuario, usuario + "@academia.com");
        exercicio = UUID.randomUUID();
        jdbc.update("INSERT INTO exercicios (id, nome, grupo_muscular, criado_em) VALUES (?, ?, 'PEITO', now())",
                exercicio, "Supino " + exercicio);
    }

    @AfterEach
    void limpar() {
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("usuarios", usuario);
        limpeza.apagar("exercicios", exercicio);
    }

    private UUID ficha(boolean encerrada) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fichas (id, aluno_id, montada_em, montada_por, encerrada_em)
                VALUES (?, ?, now(), ?, CASE WHEN ? THEN now() END)""", id, aluno, usuario, encerrada);
        return id;
    }

    private UUID divisao(UUID ficha, int posicao) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ficha_divisoes (id, ficha_id, posicao, nome) VALUES (?, ?, ?, 'A')", id, ficha, posicao);
        return id;
    }

    private void item(UUID divisao, int posicao, int series) {
        jdbc.update("""
                INSERT INTO ficha_exercicios (id, divisao_id, posicao, exercicio_id, nome, series, repeticoes)
                VALUES (?, ?, ?, ?, 'Supino', ?, '10')""", UUID.randomUUID(), divisao, posicao, exercicio, series);
    }

    /** Como a aplicação conecta em produção (nos testes, ela usa o dono do banco). */
    private int comoAplicacao(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "academia_app",
                "senha-do-app-so-para-testes"); Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        }
    }

    private int contar(String tabela, String coluna, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabela + " WHERE " + coluna + " = ?", Integer.class, id);
    }

    @Test
    void umaSoAtualPorAluno() {
        ficha(false);
        ficha(true);
        ficha(true);

        assertThatThrownBy(() -> ficha(false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_fichas_atual_por_aluno");
    }

    @Test
    void encerradaNaoMudaNemGanhaDivisoesOuExercicios() {
        UUID ficha = ficha(false);
        UUID divisao = divisao(ficha, 1);
        item(divisao, 1, 3);
        // Enquanto é a atual, muda normalmente.
        assertThatCode(() -> jdbc.update("UPDATE fichas SET revisao_em = current_date + 1 WHERE id = ?", ficha))
                .doesNotThrowAnyException();

        jdbc.update("UPDATE fichas SET encerrada_em = now() WHERE id = ?", ficha);

        assertThatThrownBy(() -> jdbc.update("UPDATE fichas SET revisao_em = current_date + 2 WHERE id = ?", ficha))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ficha encerrada não muda");
        assertThatThrownBy(() -> jdbc.update("UPDATE fichas SET encerrada_em = NULL WHERE id = ?", ficha))
                .as("nem volta a ser a atual").hasMessageContaining("ficha encerrada não muda");
        assertThatThrownBy(() -> divisao(ficha, 2)).hasMessageContaining("ficha encerrada não muda");
        assertThatThrownBy(() -> jdbc.update("UPDATE ficha_divisoes SET nome = 'B' WHERE id = ?", divisao))
                .hasMessageContaining("não se alteram");
        assertThatThrownBy(() -> item(divisao, 2, 3)).hasMessageContaining("ficha encerrada não muda");
        assertThatThrownBy(() -> jdbc.update("UPDATE ficha_exercicios SET series = 4 WHERE divisao_id = ?", divisao))
                .hasMessageContaining("não se alteram");
        assertThat(jdbc.queryForObject("SELECT series FROM ficha_exercicios WHERE divisao_id = ?", Integer.class,
                divisao)).isEqualTo(3);
    }

    /** T1 do diagnóstico da etapa 7: a V30 olhava só a linha nova, e a divisão saía da encerrada. */
    @Test
    void divisaoNaoSaiDaEncerradaNemMudaNaAtual() {
        UUID antiga = ficha(false);
        UUID daEncerrada = divisao(antiga, 1);
        item(daEncerrada, 1, 3);
        jdbc.update("UPDATE fichas SET encerrada_em = now() WHERE id = ?", antiga);
        UUID nova = ficha(false);
        UUID daAtual = divisao(nova, 1);
        item(daAtual, 1, 3);

        assertThatThrownBy(() -> jdbc.update("UPDATE ficha_divisoes SET ficha_id = ? WHERE id = ?", nova, daEncerrada))
                .hasMessageContaining("não se alteram");
        assertThatThrownBy(() -> jdbc.update("UPDATE ficha_exercicios SET divisao_id = ? WHERE divisao_id = ?",
                daAtual, daEncerrada)).hasMessageContaining("não se alteram");
        // Nem na atual: a correção apaga as divisões e grava novas.
        assertThatThrownBy(() -> jdbc.update("UPDATE ficha_divisoes SET nome = 'B' WHERE id = ?", daAtual))
                .hasMessageContaining("não se alteram");
        assertThatThrownBy(() -> jdbc.update("UPDATE ficha_exercicios SET series = 4 WHERE divisao_id = ?", daAtual))
                .hasMessageContaining("não se alteram");
        assertThat(contar("ficha_divisoes", "ficha_id", antiga)).isEqualTo(1);
        assertThat(contar("ficha_divisoes", "ficha_id", nova)).isEqualTo(1);
    }

    @Test
    void alunoMontagemEAutorDaFichaNaoMudam() {
        UUID ficha = ficha(false);
        UUID outroAluno = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Outro', ?)", outroAluno, CpfsParaTeste.aleatorio());
        try {
            for (String mudanca : new String[] {"aluno_id = '" + outroAluno + "'", "montada_por = '" + aluno + "'",
                    "montada_em = montada_em - interval '1 day'", "id = gen_random_uuid()"}) {
                assertThatThrownBy(() -> jdbc.update("UPDATE fichas SET " + mudanca + " WHERE id = ?", ficha))
                        .as(mudanca).hasMessageContaining("não mudam");
            }
            // O resto da ficha atual muda normalmente (a correção e o encerramento).
            assertThatCode(() -> jdbc.update("""
                    UPDATE fichas SET revisao_em = current_date + 30, corrigida_em = now(), versao = versao + 1,
                                      aluno_id = aluno_id, montada_por = montada_por WHERE id = ?""", ficha))
                    .doesNotThrowAnyException();
        } finally {
            limpeza.apagar("alunos", outroAluno);
        }
    }

    /**
     * A aplicação (academia_app) apaga divisões e exercícios só da ficha atual (a correção);
     * os da encerrada, só o dono do banco (a limpeza dos testes). A ficha, nunca.
     */
    @Test
    void aplicacaoNaoApagaNadaDaEncerrada() throws SQLException {
        UUID atual = ficha(false);
        UUID divisao = divisao(atual, 1);
        item(divisao, 1, 3);
        jdbc.update("UPDATE fichas SET encerrada_em = now() WHERE id = ?", atual);

        assertThatThrownBy(() -> comoAplicacao("DELETE FROM ficha_exercicios WHERE divisao_id = '" + divisao + "'"))
                .hasMessageContaining("ficha encerrada não muda");
        assertThatThrownBy(() -> comoAplicacao("DELETE FROM ficha_divisoes WHERE id = '" + divisao + "'"))
                .hasMessageContaining("ficha encerrada não muda");
        assertThatThrownBy(() -> comoAplicacao("DELETE FROM fichas WHERE id = '" + atual + "'"))
                .hasMessageContaining("permission denied");
        assertThat(contar("ficha_exercicios", "divisao_id", divisao)).isEqualTo(1);
        assertThat(contar("ficha_divisoes", "ficha_id", atual)).isEqualTo(1);

        UUID nova = ficha(false);
        UUID daNova = divisao(nova, 1);
        item(daNova, 1, 3);
        assertThat(comoAplicacao("DELETE FROM ficha_exercicios WHERE divisao_id = '" + daNova + "'")).isEqualTo(1);
        assertThat(comoAplicacao("DELETE FROM ficha_divisoes WHERE id = '" + daNova + "'")).isEqualTo(1);

        jdbc.update("DELETE FROM ficha_exercicios WHERE divisao_id = ?", divisao);
        jdbc.update("DELETE FROM ficha_divisoes WHERE id = ?", divisao);
        assertThat(contar("ficha_divisoes", "ficha_id", atual)).as("o dono apaga").isZero();
    }

    @Test
    void limitesDasDivisoesEDosExercicios() {
        UUID atual = ficha(false);
        UUID d = divisao(atual, 7);

        assertThatThrownBy(() -> divisao(atual, 8)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> divisao(atual, 0)).isInstanceOf(DataIntegrityViolationException.class);
        // Posição repetida: conferida no fim da transação (a correção troca as divisões no meio).
        assertThatThrownBy(() -> transacao.executeWithoutResult(s -> divisao(atual, 7)))
                .rootCause().hasMessageContaining("uk_ficha_divisoes_posicao");
        // Na mesma transação, a posição pode coincidir no meio do caminho, desde que não no fim.
        transacao.executeWithoutResult(s -> {
            divisao(atual, 7);
            jdbc.update("DELETE FROM ficha_divisoes WHERE id = ?", d);
        });
        UUID outra = divisao(atual, 1);
        item(outra, 20, 20);
        assertThatThrownBy(() -> item(outra, 21, 3)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> item(outra, 1, 21)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> item(outra, 1, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> item(outra, 20, 3)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void datasEmOrdem() {
        UUID ficha = ficha(false);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE fichas SET corrigida_em = montada_em - interval '1 second' WHERE id = ?", ficha))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_fichas_corrigida");
        jdbc.update("UPDATE fichas SET corrigida_em = montada_em + interval '1 hour' WHERE id = ?", ficha);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE fichas SET encerrada_em = montada_em + interval '1 minute' WHERE id = ?", ficha))
                .as("antes da correção").hasMessageContaining("ck_fichas_encerrada");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE fichas SET encerrada_em = montada_em - interval '1 second', corrigida_em = NULL WHERE id = ?",
                ficha)).as("antes da montagem").hasMessageContaining("ck_fichas_encerrada");
    }

    @Test
    void repeticoesEDescansoDoExercicio() {
        UUID d = divisao(ficha(false), 1);
        String insercao = """
                INSERT INTO ficha_exercicios (id, divisao_id, posicao, exercicio_id, nome, series, repeticoes,
                                              descanso_segundos)
                VALUES (?, ?, ?, ?, 'Supino', 3, ?, ?)""";

        jdbc.update(insercao, UUID.randomUUID(), d, 1, exercicio, "10", 600);
        jdbc.update(insercao, UUID.randomUUID(), d, 2, exercicio, "10", 0);
        assertThatThrownBy(() -> jdbc.update(insercao, UUID.randomUUID(), d, 3, exercicio, " ", 60))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insercao, UUID.randomUUID(), d, 3, exercicio, "10", 601))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insercao, UUID.randomUUID(), d, 3, exercicio, "10", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
