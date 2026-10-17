package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revisão de 2026-10-17 (ADR 0020): logins simultâneos esgotavam o pool de conexões. O
 * login segurava uma conexão (transação externa) e o limitador pedia outra (transação
 * própria); com 10 ao mesmo tempo, as 10 do pool ficavam presas esperando a segunda, e
 * o sistema inteiro parava por 30 s. Aqui, mais logins simultâneos do que conexões no
 * pool precisam terminar logo, e uma consulta qualquer no meio deles também.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LoginSimultaneoTest {

    private static final int SIMULTANEOS = 30;  // o pool dos testes tem 10 conexões
    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    AutenticacaoService autenticacao;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Usuario> usuarios = new ArrayList<>();

    @BeforeEach
    void criarUsuarios() {
        String hash = passwordEncoder.encode(SENHA);
        for (int i = 0; i < SIMULTANEOS; i++) {
            usuarios.add(repository.save(Usuario.daEquipe("Pessoa " + i,
                    "simultaneo-" + i + "-" + UUID.randomUUID() + "@teste.local", hash, Perfil.RECEPCAO)));
        }
    }

    @AfterEach
    void limpar() {
        for (Usuario u : usuarios) {
            jdbc.update("DELETE FROM tentativas_login WHERE chave = ?", u.getEmail());
            jdbc.update("DELETE FROM usuarios WHERE id = ?", u.getId());
        }
        jdbc.update("DELETE FROM tentativas_login WHERE chave LIKE '10.20.%'");
    }

    /** Larga todas juntas; devolve o resultado de cada uma e quanto demorou a consulta do meio. */
    private Resultado aoMesmoTempo(IntFunction<String> tentativa) throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        List<CompletableFuture<String>> tentativas = new ArrayList<>();
        for (int i = 0; i < SIMULTANEOS; i++) {
            int n = i;
            tentativas.add(CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    return tentativa.apply(n);
                } catch (Exception e) {
                    return "outro erro: " + e.getClass().getSimpleName();
                }
            }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()));
        }
        long inicio = System.nanoTime();
        largada.countDown();
        Thread.sleep(100);
        // Um usuário qualquer do sistema, no meio da enxurrada: precisa de uma conexão.
        long antesDaConsulta = System.nanoTime();
        jdbc.queryForObject("SELECT 1", Integer.class);
        long consultaMs = (System.nanoTime() - antesDaConsulta) / 1_000_000;
        List<String> resultados = new ArrayList<>();
        for (var t : tentativas) {
            resultados.add(t.get(60, TimeUnit.SECONDS));
        }
        return new Resultado(resultados, consultaMs, (System.nanoTime() - inicio) / 1_000_000);
    }

    private record Resultado(List<String> respostas, long consultaMs, long totalMs) {
    }

    @Test
    void loginsSimultaneosNaoEsgotamOPool() throws Exception {
        // Metade com a senha certa, metade errada; cada um de um IP, para o limite não interferir.
        Resultado r = aoMesmoTempo(i -> {
            try {
                autenticacao.autenticar(usuarios.get(i).getEmail(), i % 2 == 0 ? SENHA : "senha-errada-123", "10.20.0." + i);
                return "ok";
            } catch (CredenciaisInvalidasException e) {
                return "recusado";
            }
        });

        assertThat(r.respostas()).as("respostas").containsOnly("ok", "recusado");
        assertThat(r.respostas()).filteredOn("ok"::equals).hasSize(SIMULTANEOS / 2);
        assertThat(r.consultaMs()).as("consulta no meio dos logins (ms)").isLessThan(2_000);
        assertThat(r.totalMs()).as("todos os logins (ms)").isLessThan(15_000);
    }

    @Test
    void trocasDeSenhaSimultaneasNaoEsgotamOPool() throws Exception {
        Resultado r = aoMesmoTempo(i -> {
            try {
                autenticacao.trocarSenha(usuarios.get(i).getId(), i % 2 == 0 ? SENHA : "senha-errada-123",
                        "nova-senha-de-teste-" + i, "10.20.1." + i);
                return "trocada";
            } catch (DadosInvalidosException e) {
                return "recusada";
            }
        });

        assertThat(r.respostas()).as("respostas").containsOnly("trocada", "recusada");
        assertThat(r.respostas()).filteredOn("trocada"::equals).hasSize(SIMULTANEOS / 2);
        assertThat(r.consultaMs()).as("consulta no meio das trocas (ms)").isLessThan(2_000);
        assertThat(r.totalMs()).as("todas as trocas (ms)").isLessThan(15_000);
        // A troca valeu de verdade: a senha nova entra, e a versão do token mudou.
        Usuario trocado = repository.findById(usuarios.getFirst().getId()).orElseThrow();
        assertThat(passwordEncoder.matches("nova-senha-de-teste-0", trocado.getSenhaHash())).isTrue();
        assertThat(trocado.getVersaoToken()).isGreaterThan(usuarios.getFirst().getVersaoToken());
    }
}
