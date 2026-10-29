package br.com.academiagestao.alunos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.compartilhado.RecursoModificadoException;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Excluir o aluno ao mesmo tempo que outra operação nele (ADR 0043). As duas travam a linha do
 * aluno, então uma sempre vê o resultado da outra. Várias rodadas, e cada lado precisa ganhar em
 * parte delas: senão a corrida não teria sido testada.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ExclusaoDeAlunoConcorrenciaTest {

    private static final int RODADAS = 30;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    AlunoService service;

    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> criados = new ArrayList<>();

    @AfterEach
    void limpar() {
        for (UUID id : criados) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ? OR alteracoes -> 'alunoId' ->> 'depois' = ?",
                    id, id.toString());
            limpeza.apagar("alunos", id);
        }
        criados.clear();
    }

    private UUID aluno(String nome) {
        UUID id = UUID.randomUUID();
        String cpf = CpfsParaTeste.aleatorio();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email) VALUES (?, ?, ?, ?)", id, nome, cpf, cpf + "@teste.local");
        criados.add(id);
        return id;
    }

    /** Larga as duas tarefas juntas, a segunda com um atraso aleatório, e devolve os resultados. */
    private static List<String> juntas(Callable<String> primeira, Callable<String> segunda) throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        long atraso = ThreadLocalRandom.current().nextLong(3);
        List<CompletableFuture<String>> futuros = new ArrayList<>();
        for (Callable<String> tarefa : List.of(primeira, () -> { Thread.sleep(atraso); return segunda.call(); })) {
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

    /**
     * Criar o login trava o aluno: ou a exclusão chega antes (e o login não acha o aluno, 404),
     * ou o login chega antes, e a exclusão leva os dois (o login nunca foi usado). Sem a trava, o
     * login esbarrava na chave estrangeira e respondia "já tem login".
     */
    @Test
    void criarOLoginEExcluirAoMesmoTempo() throws Exception {
        Set<String> vencedores = new HashSet<>();
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID aluno = aluno("Aluno " + rodada);

            List<String> resultados = juntas(() -> { service.criarLogin(aluno); return "criado"; },
                    () -> { service.excluir(aluno); return "excluído"; });

            assertThat(resultados.get(1)).as("rodada %d (login: %s)", rodada, resultados.getFirst()).isEqualTo("excluído");
            assertThat(resultados.getFirst()).as("rodada %d", rodada).isIn("criado", "AlunoNaoEncontradoException");
            vencedores.add(resultados.getFirst());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios WHERE aluno_id = ?", Integer.class, aluno)).isZero();
            jdbc.update("DELETE FROM auditoria WHERE alteracoes -> 'alunoId' ->> 'antes' = ?", aluno.toString());
        }
        assertThat(vencedores).as("cada lado chegou antes em parte das rodadas")
                .containsExactlyInAnyOrder("criado", "AlunoNaoEncontradoException");
    }

    /**
     * A exclusão trava o aluno antes do retrato: a edição ao mesmo tempo acontece antes (e a
     * trilha guarda o nome editado) ou depois (404 ou 412). A exclusão nunca falha.
     */
    @Test
    void editarEExcluirAoMesmoTempo() throws Exception {
        Set<String> edicoes = new HashSet<>();
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID aluno = aluno("Aluno " + rodada);
            String cpf = jdbc.queryForObject("SELECT cpf FROM alunos WHERE id = ?", String.class, aluno);
            String editado = "Editado " + rodada;

            List<String> resultados = juntas(() -> {
                try {
                    service.atualizar(aluno, new DadosAluno(editado, cpf, null, null, null, null), "*");
                    return "editado";
                } catch (AlunoNaoEncontradoException | RecursoModificadoException e) {
                    return "depois";
                }
            }, () -> { service.excluir(aluno); return "excluído"; });

            assertThat(resultados.get(1)).as("rodada %d (edição: %s)", rodada, resultados.getFirst()).isEqualTo("excluído");
            assertThat(resultados.getFirst()).as("rodada %d", rodada).isIn("editado", "depois");
            edicoes.add(resultados.getFirst());
            assertThat(jdbc.queryForObject("""
                    SELECT alteracoes -> 'nome' ->> 'antes' FROM auditoria
                    WHERE entidade = 'ALUNO' AND entidade_id = ? AND acao = 'EXCLUIDO'""", String.class, aluno))
                    .as("rodada %d", rodada).isEqualTo(resultados.getFirst().equals("editado") ? editado : "Aluno " + rodada);
        }
        assertThat(edicoes).as("cada lado chegou antes em parte das rodadas").containsExactlyInAnyOrder("editado", "depois");
    }
}
