package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Editar e excluir o mesmo login da equipe ao mesmo tempo (ADR 0043). A exclusão trava a linha
 * antes do retrato: se a edição chega antes, a trilha guarda o nome editado. Sem a trava, a
 * exclusão tirava o retrato antes da edição e o guardava velho. Várias rodadas, cada lado
 * ganhando em parte delas.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ExclusaoDaEquipeConcorrenciaTest {

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    EquipeService service;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void limpar() {
        limpeza.apagarTudo();
    }

    @Test
    void editarEExcluirAoMesmoTempo() throws Exception {
        UUID quemPede = UUID.randomUUID();
        Set<String> edicoes = new HashSet<>();
        for (int rodada = 0; rodada < 40; rodada++) {
            String editado = "Editado " + rodada;
            UUID id = repository.save(Usuario.daEquipe("Rita " + rodada, "rita" + rodada + "@academia.com", "x",
                    Perfil.RECEPCAO)).getId();
            CountDownLatch largada = new CountDownLatch(1);
            long atraso = ThreadLocalRandom.current().nextLong(3);
            CompletableFuture<String> exclusao = CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    Thread.sleep(atraso);
                    service.excluir(id, quemPede);
                    return "excluído";
                } catch (Exception e) {
                    return "erro: " + e.getClass().getSimpleName();
                }
            });
            CompletableFuture<String> edicao = CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    service.editar(id, editado, Perfil.RECEPCAO, null, null, quemPede);
                    return "editado";
                } catch (Exception e) {
                    // Depois da exclusão, a edição não acha mais a linha: o erro é dela, não da exclusão.
                    return "depois";
                }
            });
            largada.countDown();
            String resultadoExclusao = exclusao.get(10, TimeUnit.SECONDS);
            String resultadoEdicao = edicao.get(10, TimeUnit.SECONDS);

            assertThat(resultadoExclusao).as("rodada %d (edição: %s)", rodada, resultadoEdicao).isEqualTo("excluído");
            edicoes.add(resultadoEdicao);
            String nomeNaTrilha = jdbc.queryForObject("""
                    SELECT alteracoes -> 'nome' ->> 'antes' FROM auditoria
                    WHERE entidade = 'USUARIO' AND entidade_id = ? AND acao = 'EXCLUIDO'""", String.class, id);
            assertThat(nomeNaTrilha).as("rodada %d", rodada)
                    .isEqualTo(resultadoEdicao.equals("editado") ? editado : "Rita " + rodada);
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", id);
        }
        assertThat(edicoes).as("cada lado chegou antes em parte das rodadas").containsExactlyInAnyOrder("editado", "depois");
    }
}
