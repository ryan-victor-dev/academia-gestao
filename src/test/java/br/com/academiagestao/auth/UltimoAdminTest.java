package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A desativa B enquanto B desativa A. Sem travar as linhas, as duas checagens veriam
 * "2 ADMINs ativos", as duas passariam e a academia ficaria sem nenhum ADMIN.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UltimoAdminTest {

    @Autowired
    EquipeService equipeService;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void desativacaoCruzadaSimultaneaNuncaDeixaAAcademiaSemAdmin() throws Exception {
        for (int rodada = 0; rodada < 30; rodada++) {
            jdbc.update("DELETE FROM usuarios");
            Usuario a = repository.save(Usuario.daEquipe("A", "a@academia.com", "hash", Perfil.ADMIN));
            Usuario b = repository.save(Usuario.daEquipe("B", "b@academia.com", "hash", Perfil.ADMIN));

            CountDownLatch largada = new CountDownLatch(1);
            CompletableFuture<Void> aDesativaB = CompletableFuture.runAsync(() -> tentar(largada, () ->
                    equipeService.desativar(b.getId(), a.getId())));
            CompletableFuture<Void> bDesativaA = CompletableFuture.runAsync(() -> tentar(largada, () ->
                    equipeService.desativar(a.getId(), b.getId())));
            largada.countDown();
            CompletableFuture.allOf(aDesativaB, bDesativaA).get(10, TimeUnit.SECONDS);

            Integer adminsAtivos = jdbc.queryForObject(
                    "SELECT count(*) FROM usuarios WHERE perfil = 'ADMIN' AND ativo", Integer.class);
            assertThat(adminsAtivos).as("ADMINs ativos na rodada %d", rodada).isEqualTo(1);
        }
    }

    private static void tentar(CountDownLatch largada, Runnable acao) {
        try {
            largada.await();
            acao.run();
        } catch (OperacaoNaoPermitidaException e) {
            // Esperado para quem chegou em segundo.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
