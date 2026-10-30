package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tira B (desativando ou rebaixando) enquanto B tira A. Sem travar as linhas, as duas
 * checagens veriam "2 ADMINs ativos", as duas passariam e a academia ficaria sem ADMIN.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UltimoAdminTest {

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    EquipeService equipeService;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void desativacaoCruzadaSimultaneaNuncaDeixaAAcademiaSemAdmin() throws Exception {
        corrida((a, b) -> equipeService.desativar(b, a), (a, b) -> equipeService.desativar(a, b));
    }

    @Test
    void rebaixamentoCruzadoSimultaneoNuncaDeixaAAcademiaSemAdmin() throws Exception {
        corrida((a, b) -> equipeService.editar(b, "B", Perfil.RECEPCAO, null, a),
                (a, b) -> equipeService.editar(a, "A", Perfil.RECEPCAO, null, b));
    }

    @Test
    void desativacaoERebaixamentoSimultaneosNuncaDeixamAAcademiaSemAdmin() throws Exception {
        corrida((a, b) -> equipeService.desativar(b, a),
                (a, b) -> equipeService.editar(a, "A", Perfil.INSTRUTOR, null, b));
    }

    /** 30 rodadas: com dois ADMINs, cada um tenta tirar o outro ao mesmo tempo. Sempre sobra um. */
    private void corrida(BiConsumer<UUID, UUID> acaoDeA, BiConsumer<UUID, UUID> acaoDeB) throws Exception {
        for (int rodada = 0; rodada < 30; rodada++) {
            limpeza.apagarTudo();
            UUID a = repository.save(Usuario.daEquipe("A", "a@academia.com", "hash", Perfil.ADMIN)).getId();
            UUID b = repository.save(Usuario.daEquipe("B", "b@academia.com", "hash", Perfil.ADMIN)).getId();

            CountDownLatch largada = new CountDownLatch(1);
            CompletableFuture<Void> deA = CompletableFuture.runAsync(() -> tentar(largada, () -> acaoDeA.accept(a, b)));
            CompletableFuture<Void> deB = CompletableFuture.runAsync(() -> tentar(largada, () -> acaoDeB.accept(a, b)));
            largada.countDown();
            CompletableFuture.allOf(deA, deB).get(10, TimeUnit.SECONDS);

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
