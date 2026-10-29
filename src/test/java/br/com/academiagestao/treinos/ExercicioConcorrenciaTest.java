package br.com.academiagestao.treinos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.compartilhado.RecursoModificadoException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dois cadastros com o mesmo nome ao mesmo tempo passam juntos pela checagem de nome;
 * quem barra o segundo é o índice único do banco. Esse erro tem que virar 409 (nome em
 * uso), nunca um erro genérico (500). E editar e excluir ao mesmo tempo (ADR 0043).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ExercicioConcorrenciaTest {

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    ExercicioService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void cadastrosSimultaneosComOMesmoNome() throws Exception {
        for (int rodada = 0; rodada < 20; rodada++) {
            limpeza.apagarTudo();
            CountDownLatch largada = new CountDownLatch(1);
            List<CompletableFuture<String>> tentativas = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                tentativas.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        largada.await();
                        service.cadastrar(new DadosExercicio("Supino", GrupoMuscular.PEITO, null));
                        return "criado";
                    } catch (NomeDeExercicioEmUsoException e) {
                        return "409";
                    } catch (Exception e) {
                        return "outro erro: " + e.getClass().getSimpleName();
                    }
                }));
            }
            largada.countDown();
            List<String> resultados = new ArrayList<>();
            for (var t : tentativas) {
                resultados.add(t.get(10, TimeUnit.SECONDS));
            }

            assertThat(resultados).as("rodada %d", rodada).containsExactlyInAnyOrder("criado", "409");
        }
    }

    /**
     * A exclusão trava o exercício antes de tirar o retrato: a edição ao mesmo tempo acontece
     * antes (e a trilha guarda o nome editado) ou depois (404 ou 412). A exclusão nunca falha:
     * sem a trava, o DELETE com a versão de antes da edição não acharia a linha (500).
     */
    @Test
    void editarEExcluirAoMesmoTempo() throws Exception {
        Set<String> edicoes = new HashSet<>();
        for (int rodada = 0; rodada < 40; rodada++) {
            String editado = "Editado " + rodada;
            UUID id = service.cadastrar(new DadosExercicio("Supino " + rodada, GrupoMuscular.PEITO, null)).getId();
            CountDownLatch largada = new CountDownLatch(1);
            long atraso = ThreadLocalRandom.current().nextLong(3);
            CompletableFuture<String> exclusao = CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    Thread.sleep(atraso);
                    service.excluir(id);
                    return "excluído";
                } catch (Exception e) {
                    return "erro: " + e.getClass().getSimpleName();
                }
            });
            CompletableFuture<String> edicao = CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    service.atualizar(id, new DadosExercicio(editado, GrupoMuscular.PEITO, null), "*");
                    return "editado";
                } catch (ExercicioNaoEncontradoException | RecursoModificadoException e) {
                    return "depois";
                } catch (Exception e) {
                    return "erro: " + e.getClass().getSimpleName();
                }
            });
            largada.countDown();
            String resultadoExclusao = exclusao.get(10, TimeUnit.SECONDS);
            String resultadoEdicao = edicao.get(10, TimeUnit.SECONDS);

            assertThat(resultadoExclusao).as("rodada %d (edição: %s)", rodada, resultadoEdicao).isEqualTo("excluído");
            assertThat(resultadoEdicao).as("rodada %d", rodada).isIn("editado", "depois");
            edicoes.add(resultadoEdicao);
            String nomeNaTrilha = jdbc.queryForObject("""
                    SELECT alteracoes -> 'nome' ->> 'antes' FROM auditoria
                    WHERE entidade = 'EXERCICIO' AND entidade_id = ? AND acao = 'EXCLUIDO'""", String.class, id);
            assertThat(nomeNaTrilha).as("rodada %d", rodada)
                    .isEqualTo(resultadoEdicao.equals("editado") ? editado : "Supino " + rodada);
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", id);
        }
        assertThat(edicoes).as("cada lado chegou antes em parte das rodadas").containsExactlyInAnyOrder("editado", "depois");
    }
}
