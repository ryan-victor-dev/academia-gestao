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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O que o banco garante das fichas, mesmo que um erro no código tente o contrário (V30):
 * uma atual por aluno, e a encerrada não muda.
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
                .hasMessageContaining("ficha encerrada não muda");
        assertThatThrownBy(() -> item(divisao, 2, 3)).hasMessageContaining("ficha encerrada não muda");
        assertThatThrownBy(() -> jdbc.update("UPDATE ficha_exercicios SET series = 4 WHERE divisao_id = ?", divisao))
                .hasMessageContaining("ficha encerrada não muda");
        assertThat(jdbc.queryForObject("SELECT series FROM ficha_exercicios WHERE divisao_id = ?", Integer.class,
                divisao)).isEqualTo(3);
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
