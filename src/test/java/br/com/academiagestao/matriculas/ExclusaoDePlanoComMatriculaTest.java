package br.com.academiagestao.matriculas;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * Excluir o plano e matricular nele ao mesmo tempo (ADR 0043): a exclusão trava o plano, e a
 * matrícula o lê com trava. Uma sempre vê o resultado da outra: ou o plano sai e a matrícula não
 * o acha (400), ou a matrícula fica e a exclusão é recusada (409). Nunca um 500 da chave
 * estrangeira. Várias rodadas: uma só poderia passar por sorte.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExclusaoDePlanoComMatriculaTest {

    private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    MatriculaService matriculas;

    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> alunos = new ArrayList<>();
    private final List<UUID> planos = new ArrayList<>();

    @AfterEach
    void limpar() {
        alunos.forEach(id -> limpeza.apagar("alunos", id));
        planos.forEach(id -> limpeza.apagar("planos", id));
    }

    @Test
    void excluirOPlanoEMatricularNeleAoMesmoTempo() throws Exception {
        int excluidos = 0;
        int matriculados = 0;
        for (int rodada = 0; rodada < 30; rodada++) {
            UUID plano = UUID.randomUUID();
            jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 1)",
                    plano, "Plano " + plano);
            planos.add(plano);
            UUID aluno = UUID.randomUUID();
            jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
            alunos.add(aluno);

            CountDownLatch largada = new CountDownLatch(1);
            CompletableFuture<Integer> exclusao = CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    return mvc.perform(delete("/api/planos/" + plano).with(ADMIN)).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    return -1;
                }
            });
            // A exclusão passa pelo MockMvc e chega depois; um atraso aleatório na matrícula faz
            // cada lado ganhar em parte das rodadas.
            long atraso = ThreadLocalRandom.current().nextLong(4);
            CompletableFuture<String> matricula = CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    Thread.sleep(atraso);
                    matriculas.matricular(aluno, plano, null);
                    return "criada";
                } catch (DadosInvalidosException e) {
                    return "400 " + e.getMessage();
                } catch (Exception e) {
                    return "outro erro: " + e.getClass().getSimpleName();
                }
            });
            largada.countDown();
            int statusExclusao = exclusao.get(20, TimeUnit.SECONDS);
            String resultadoMatricula = matricula.get(20, TimeUnit.SECONDS);

            int planoExiste = jdbc.queryForObject("SELECT count(*) FROM planos WHERE id = ?", Integer.class, plano);
            int matriculasDoAluno = jdbc.queryForObject("SELECT count(*) FROM matriculas WHERE aluno_id = ?",
                    Integer.class, aluno);
            if (statusExclusao == 204) {
                excluidos++;
                assertThat(resultadoMatricula).as("rodada %d", rodada).isEqualTo("400 plano " + plano + " não encontrado");
                assertThat(planoExiste).isZero();
                assertThat(matriculasDoAluno).isZero();
            } else {
                matriculados++;
                assertThat(statusExclusao).as("rodada %d (matrícula: %s)", rodada, resultadoMatricula).isEqualTo(409);
                assertThat(resultadoMatricula).isEqualTo("criada");
                assertThat(planoExiste).isOne();
                assertThat(matriculasDoAluno).isOne();
            }
        }
        // Se um lado nunca ganhasse, a corrida não teria sido testada de verdade.
        System.out.printf("excluirOPlanoEMatricularNeleAoMesmoTempo: %d excluídos, %d matriculados%n",
                excluidos, matriculados);
    }
}
