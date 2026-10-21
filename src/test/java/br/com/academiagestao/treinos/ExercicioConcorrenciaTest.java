package br.com.academiagestao.treinos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dois cadastros com o mesmo nome ao mesmo tempo passam juntos pela checagem de nome;
 * quem barra o segundo é o índice único do banco. Esse erro tem que virar 409 (nome em
 * uso), nunca um erro genérico (500).
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
}
