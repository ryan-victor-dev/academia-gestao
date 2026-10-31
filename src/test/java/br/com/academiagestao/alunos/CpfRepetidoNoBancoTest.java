package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static br.com.academiagestao.alunos.NascimentoParaTeste.NASCIMENTO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O CPF repetido que passa pela checagem (existsByCpf) e só é barrado pelo índice único do
 * banco também vira "CPF já cadastrado". Sem sorte: outra transação insere o CPF e segura
 * sem confirmar; o cadastro não vê a linha, passa pela checagem e espera no índice até a
 * outra confirmar. Confere, com o banco de verdade, o nome do índice que o AlunoService
 * reconhece (revisão de 2026-10-17: só ele vira 409).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CpfRepetidoNoBancoTest {

    @Autowired
    AlunoService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transacoes;

    private final String cpf = CpfsParaTeste.aleatorio();

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM alunos WHERE cpf = ?", cpf);
    }

    @Test
    void cpfBarradoSoPeloIndiceViraCpfJaCadastrado() throws Exception {
        CountDownLatch inserido = new CountDownLatch(1);
        CompletableFuture<Void> outraTransacao = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transacoes).executeWithoutResult(s -> {
                    jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Primeira', ?)", UUID.randomUUID(), cpf);
                    inserido.countDown();
                    try {
                        Thread.sleep(1_000); // o cadastro abaixo chega ao índice e fica esperando
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }), Executors.newVirtualThreadPerTaskExecutor());
        assertThat(inserido.await(10, TimeUnit.SECONDS)).isTrue();

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> service.cadastrar(new DadosAluno("Segunda", cpf, null, null, NASCIMENTO, null)))
                .isInstanceOf(CpfJaCadastradoException.class);
        outraTransacao.get(10, TimeUnit.SECONDS);

        // Esperou a outra transação: a recusa veio do índice, e não da checagem.
        assertThat((System.nanoTime() - inicio) / 1_000_000).as("espera (ms)").isGreaterThan(500);
    }
}
