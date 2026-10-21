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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Montar e corrigir fichas do mesmo aluno ao mesmo tempo. A trava na linha do aluno faz um
 * esperar o outro: nunca duas atuais, nunca um erro genérico (ADR 0027).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FichaConcorrenciaTest {

    private static final int RODADAS = 20;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    FichaService service;

    @Autowired
    JdbcTemplate jdbc;

    private UUID instrutor;
    private UUID exercicio;

    @BeforeEach
    void preparar() {
        limpeza.apagarTudo();
        instrutor = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'I', ?, 'x', 'INSTRUTOR')",
                instrutor, instrutor + "@academia.com");
        exercicio = UUID.randomUUID();
        jdbc.update("INSERT INTO exercicios (id, nome, grupo_muscular, criado_em) VALUES (?, 'Supino', 'PEITO', now())",
                exercicio);
    }

    @AfterEach
    void limpar() {
        limpeza.apagarTudo();
    }

    private UUID novoAluno() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", id, CpfsParaTeste.aleatorio());
        return id;
    }

    private DadosFicha ficha(String divisao) {
        return new DadosFicha(null, List.of(new DadosFicha.Divisao(divisao,
                List.of(new DadosFicha.Item(exercicio, 3, "10", null, null, null)))));
    }

    /** Larga as tarefas juntas e devolve o resultado de cada uma ("ok" ou o nome do erro). */
    private static List<String> juntas(List<Callable<String>> tarefas) throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        List<CompletableFuture<String>> futuros = new ArrayList<>();
        for (Callable<String> tarefa : tarefas) {
            futuros.add(CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    return tarefa.call();
                } catch (Exception e) {
                    return e.getClass().getSimpleName();
                }
            }));
        }
        largada.countDown();
        List<String> resultados = new ArrayList<>();
        for (var futuro : futuros) {
            resultados.add(futuro.get(10, TimeUnit.SECONDS));
        }
        return resultados;
    }

    @Test
    void duasFichasAoMesmoTempoParaOMesmoAluno() throws Exception {
        Set<String> atuaisNoFim = new HashSet<>();
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID aluno = novoAluno();
            service.montar(aluno, ficha("Primeira"), instrutor);

            List<String> resultados = juntas(List.of(
                    () -> { service.montar(aluno, ficha("X"), instrutor); return "ok"; },
                    () -> { service.montar(aluno, ficha("Y"), instrutor); return "ok"; }));

            assertThat(resultados).as("rodada %d", rodada).containsExactly("ok", "ok");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fichas WHERE aluno_id = ? AND encerrada_em IS NULL",
                    Integer.class, aluno)).as("rodada %d: uma só atual", rodada).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fichas WHERE aluno_id = ?", Integer.class, aluno))
                    .isEqualTo(3);
            atuaisNoFim.add(jdbc.queryForObject("""
                    SELECT d.nome FROM fichas f JOIN ficha_divisoes d ON d.ficha_id = f.id
                    WHERE f.aluno_id = ? AND f.encerrada_em IS NULL""", String.class, aluno));
        }
        // Cada lado terminou como a atual em parte das rodadas: a corrida aconteceu nos dois sentidos.
        assertThat(atuaisNoFim).containsExactlyInAnyOrder("X", "Y");
    }

    @Test
    void fichaNovaECorrecaoDaAtualAoMesmoTempo() throws Exception {
        Set<String> correcoes = new HashSet<>();
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID aluno = novoAluno();
            Ficha atual = service.montar(aluno, ficha("Antiga"), instrutor);

            // A correção faz uma consulta a mais antes da trava e perderia sempre: em parte das
            // rodadas, a ficha nova sai alguns milissegundos depois, para cada lado chegar antes.
            long atraso = rodada % 4;
            List<String> resultados = juntas(List.of(
                    () -> { Thread.sleep(atraso); service.montar(aluno, ficha("Nova"), instrutor); return "ok"; },
                    () -> { service.corrigir(atual.getId(), ficha("Corrigida"), "\"0\""); return "ok"; }));

            // A correção ou chega antes (e a ficha corrigida é encerrada em seguida) ou depois, e
            // vê a ficha já encerrada (409). Nunca um conflito de versão nem erro genérico.
            assertThat(resultados.getFirst()).as("rodada %d: ficha nova", rodada).isEqualTo("ok");
            assertThat(resultados.get(1)).as("rodada %d: correção", rodada).isIn("ok", "FichaRecusadaException");
            correcoes.add(resultados.get(1));
            assertThat(jdbc.queryForList("""
                    SELECT d.nome FROM fichas f JOIN ficha_divisoes d ON d.ficha_id = f.id
                    WHERE f.aluno_id = ? AND f.encerrada_em IS NULL""", String.class, aluno))
                    .as("rodada %d", rodada).containsExactly("Nova");
        }
        assertThat(correcoes).as("cada lado chegou antes em parte das rodadas")
                .containsExactlyInAnyOrder("ok", "FichaRecusadaException");
    }

    @Test
    void duasCorrecoesComOMesmoEtag() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID aluno = novoAluno();
            Ficha atual = service.montar(aluno, ficha("Antiga"), instrutor);

            List<String> resultados = juntas(List.of(
                    () -> { service.corrigir(atual.getId(), ficha("X"), "\"0\""); return "ok"; },
                    () -> { service.corrigir(atual.getId(), ficha("Y"), "\"0\""); return "ok"; }));

            assertThat(resultados).as("rodada %d", rodada)
                    .containsExactlyInAnyOrder("ok", "RecursoModificadoException");
        }
    }
}
