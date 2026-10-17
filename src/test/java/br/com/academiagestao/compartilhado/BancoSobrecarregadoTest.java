package br.com.academiagestao.compartilhado;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Revisão de 2026-10-17 (M2): com a espera padrão de 30 s do pool e nenhum tempo máximo
 * por consulta, uma consulta presa ou um pico de acessos deixava os usuários esperando
 * meio minuto por um 500. Agora a espera tem teto e a resposta diz para tentar de novo.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BancoSobrecarregadoTest {

    private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HikariDataSource pool;

    @Autowired
    PlatformTransactionManager transacoes;

    @Autowired
    PasswordEncoder passwordEncoder;

    private final CountDownLatch soltar = new CountDownLatch(1);
    private final List<CompletableFuture<?>> presas = new ArrayList<>();
    private UUID alunoId;
    private String email;

    @AfterEach
    void limpar() throws Exception {
        soltar.countDown();
        for (var presa : presas) {
            presa.get(30, TimeUnit.SECONDS);
        }
        if (alunoId != null) {
            jdbc.update("DELETE FROM alunos WHERE id = ?", alunoId);
        }
        if (email != null) {
            jdbc.update("DELETE FROM tentativas_login WHERE chave = ?", email);
            jdbc.update("DELETE FROM usuarios WHERE email = ?", email);
        }
    }

    /** Abre uma transação, roda o SQL e fica parada com a conexão (e as travas) até o fim do teste. */
    private void prenderConexao(String sql, Object... parametros) throws InterruptedException {
        CountDownLatch pronta = new CountDownLatch(1);
        presas.add(CompletableFuture.runAsync(() -> new TransactionTemplate(transacoes).executeWithoutResult(s -> {
            jdbc.queryForList(sql, parametros);
            pronta.countDown();
            try {
                // Teto: se o teste falhar antes de soltar, a conexão volta mesmo assim.
                soltar.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), Executors.newVirtualThreadPerTaskExecutor()));
        assertThat(pronta.await(10, TimeUnit.SECONDS)).as("conexão presa").isTrue();
    }

    @Test
    void consultaPresaNumaTravaParaEmCincoSegundosCom503() throws Exception {
        alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, data_nascimento, ativo, versao) VALUES (?, 'Ana', ?, '1990-01-01', true, 0)",
                alunoId, CpfsParaTeste.aleatorio());
        // Outra transação trava a linha do aluno e não solta (como uma consulta lenta ou travada).
        prenderConexao("SELECT id FROM alunos WHERE id = ? FOR UPDATE", alunoId);

        long inicio = System.nanoTime();
        MvcResult resultado = mvc.perform(put("/api/alunos/" + alunoId).with(ADMIN).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"Ana Maria\", \"cpf\": \"%s\", \"dataNascimento\": \"1990-01-01\"}"
                                .formatted(jdbc.queryForObject("SELECT cpf FROM alunos WHERE id = ?", String.class, alunoId))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.title").value("Sistema sobrecarregado"))
                .andReturn();
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(ms).as("espera (ms)").isBetween(4_500L, 10_000L);
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("statement", "SQL", "canceling");
    }

    @Test
    void poolEsgotadoRespondeEmTresSegundosCom503() throws Exception {
        for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
            prenderConexao("SELECT 1");
        }

        long inicio = System.nanoTime();
        mvc.perform(get("/api/planos").with(ADMIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.title").value("Sistema sobrecarregado"));
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(ms).as("espera (ms)").isBetween(2_500L, 6_000L);
    }

    /*
     * Com token de verdade (e não o simulado dos outros testes), a validação do token consulta
     * o banco ainda no filtro de segurança, antes do controller. Na validação no Docker, foi
     * por aí que o pool esgotado ainda saía como 500.
     */
    @Test
    void poolEsgotadoNaValidacaoDoTokenTambemDa503() throws Exception {
        email = "sobrecarga-" + UUID.randomUUID() + "@teste.local";
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Sobrecarga', ?, ?, 'ADMIN')",
                UUID.randomUUID(), email, passwordEncoder.encode("senha-de-teste-123"));
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"senha-de-teste-123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(corpo, "$.token");
        for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
            prenderConexao("SELECT 1");
        }

        mvc.perform(get("/api/planos").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.title").value("Sistema sobrecarregado"));
    }

    @Test
    void oPoolTemMaisConexoesQueOPadrao() {
        assertThat(pool.getMaximumPoolSize()).isEqualTo(20);
        assertThat(pool.getConnectionTimeout()).isEqualTo(3_000);
    }
}
