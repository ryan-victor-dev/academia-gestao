package br.com.academiagestao.matriculas;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matrículas e cancelamentos simultâneos (ADR 0016). Cada cenário roda várias vezes:
 * uma rodada só poderia passar por sorte, sem as tentativas se cruzarem de verdade.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MatriculaConcorrenciaTest {

    private static final int RODADAS = 20;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MatriculaService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RelogioDeTeste relogio;

    // Relógio parado: "hoje" não vira à meia-noite entre o teste e a aplicação.
    @BeforeEach
    void pararRelogio() {
        relogio.parar();
    }

    @AfterEach
    void restaurarRelogio() {
        relogio.restaurar();
    }

    @Autowired
    TransactionTemplate transacao;

    private final List<UUID> alunos = new ArrayList<>();
    private UUID plano;
    private UUID usuario;

    @BeforeEach
    void preparar() {
        plano = UUID.randomUUID();
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 1)",
                plano, "Plano " + plano);
        usuario = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Recepção', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
    }

    @AfterEach
    void limpar() {
        for (UUID id : alunos) {
            limpeza.apagar("alunos", id);
        }
        alunos.clear();
        limpeza.apagar("usuarios", usuario);
        limpeza.apagar("planos", plano);
    }

    private UUID criarAluno() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", id, CpfsParaTeste.aleatorio());
        alunos.add(id);
        return id;
    }

    /** Larga todas as tentativas juntas e devolve o resultado de cada uma. */
    private static List<String> aoMesmoTempo(int quantas, IntFunction<String> tentativa) throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        List<CompletableFuture<String>> tentativas = new ArrayList<>();
        for (int i = 0; i < quantas; i++) {
            int numero = i;
            tentativas.add(CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    return tentativa.apply(numero);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrompida";
                }
            }));
        }
        largada.countDown();
        List<String> resultados = new ArrayList<>();
        for (var t : tentativas) {
            resultados.add(t.get(20, TimeUnit.SECONDS));
        }
        return resultados;
    }

    private String tentarMatricular(UUID aluno, LocalDate inicio) {
        try {
            service.matricular(aluno, plano, inicio);
            return "criada";
        } catch (MatriculaRecusadaException e) {
            return "409 " + e.titulo();
        } catch (Exception e) {
            return "outro erro: " + e.getClass().getSimpleName();
        }
    }

    @Test
    void matriculasSimultaneasDoMesmoAlunoNoMesmoPeriodo() throws Exception {
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), Matricula.FUSO);
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID aluno = criarAluno();
            // Quatro períodos diferentes (começando a cada 5 dias), todos se cruzando: só um fica.
            List<String> resultados = aoMesmoTempo(4, i -> tentarMatricular(aluno, hoje.plusDays(5L * i)));

            assertThat(resultados).as("rodada %d", rodada)
                    .containsOnlyOnce("criada")
                    .filteredOn(r -> !r.equals("criada")).containsOnly("409 Período ocupado");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM matriculas WHERE aluno_id = ?", Integer.class, aluno))
                    .isEqualTo(1);
        }
    }

    @Test
    void cancelamentosSimultaneosDaMesmaMatricula() throws Exception {
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID id = service.matricular(criarAluno(), plano, null).getId();

            List<String> resultados = aoMesmoTempo(2, i -> {
                try {
                    service.cancelar(id, "Motivo " + UUID.randomUUID(), usuario);
                    return "cancelada";
                } catch (MatriculaNaoCancelavelException e) {
                    return "409";
                } catch (Exception e) {
                    return "outro erro: " + e.getClass().getSimpleName();
                }
            });

            assertThat(resultados).as("rodada %d", rodada).containsExactlyInAnyOrder("cancelada", "409");
        }
    }

    /**
     * A última garantia: uma matrícula gravada por fora (sem passar pela trava do aluno)
     * ainda não confirmada quando o service confere. O service não a vê, tenta gravar,
     * espera a outra transação e esbarra na restrição do banco. Tem que virar 409, não 500.
     */
    @Test
    void restricaoDoBancoViraPeriodoOcupado() throws Exception {
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), Matricula.FUSO);
        for (int rodada = 0; rodada < 5; rodada++) {
            UUID aluno = criarAluno();
            CountDownLatch inserida = new CountDownLatch(1);
            CountDownLatch confirmar = new CountDownLatch(1);
            CompletableFuture<Void> porFora = CompletableFuture.runAsync(() -> transacao.executeWithoutResult(s -> {
                jdbc.update("""
                        INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                        VALUES (?, ?, ?, 'Mensal', 100, 1, ?, ?, now())""",
                        UUID.randomUUID(), aluno, plano, Date.valueOf(hoje), Date.valueOf(hoje.plusMonths(1).minusDays(1)));
                inserida.countDown();
                try {
                    confirmar.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(inserida.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<String> pelaApi = CompletableFuture.supplyAsync(() -> tentarMatricular(aluno, hoje));
            // Só confirma a outra transação quando o service já está esperando por ela no INSERT.
            esperarAlguemBloqueado();
            confirmar.countDown();
            porFora.get(20, TimeUnit.SECONDS);

            assertThat(pelaApi.get(20, TimeUnit.SECONDS)).as("rodada %d", rodada).isEqualTo("409 Período ocupado");
        }
    }

    private void esperarAlguemBloqueado() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer bloqueados = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'insert into matriculas%'",
                    Integer.class);
            if (bloqueados != null && bloqueados > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("o INSERT do service não chegou a esperar pela outra transação");
    }

    /**
     * Trancar (que estende o fim) e criar a renovação para o dia seguinte ao fim, ao mesmo
     * tempo. Só um pode ficar: com o trancamento, a renovação invade o fim estendido; com a
     * renovação, o trancamento invadiria ela. Nunca um 500, nunca os dois.
     */
    @Test
    void trancarERenovarAoMesmoTempo() throws Exception {
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), Matricula.FUSO);
        int trancadas = 0;
        int renovadas = 0;
        for (int rodada = 0; rodada < RODADAS; rodada++) {
            UUID aluno = criarAluno();
            Matricula atual = service.matricular(aluno, plano, null);
            LocalDate diaDaRenovacao = atual.getFim().plusDays(1);
            long atraso = ThreadLocalRandom.current().nextLong(4);

            List<String> resultados = aoMesmoTempo(2, i -> {
                try {
                    if (i == 0) {
                        service.trancar(atual.getId(), null, hoje.plusDays(10), "Viagem", usuario);
                        return "trancada";
                    }
                    // O trancar faz uma consulta a mais antes de travar o aluno e chegaria sempre
                    // depois; um atraso aleatório faz cada lado ganhar em parte das rodadas.
                    Thread.sleep(atraso);
                    service.matricular(aluno, plano, diaDaRenovacao);
                    return "renovada";
                } catch (MatriculaRecusadaException e) {
                    return "409 " + e.titulo();
                } catch (Exception e) {
                    return "outro erro: " + e.getClass().getSimpleName();
                }
            });

            if (resultados.get(0).equals("trancada")) {
                trancadas++;
                assertThat(resultados.get(1)).as("rodada %d", rodada).isEqualTo("409 Período ocupado");
            } else {
                renovadas++;
                assertThat(resultados).as("rodada %d", rodada).containsExactly("409 Trancamento recusado", "renovada");
            }
        }
        System.out.printf("trancarERenovarAoMesmoTempo: %d trancadas, %d renovadas%n", trancadas, renovadas);
    }

    @Test
    void duasTrocasSimultaneasDaMesmaMatricula() throws Exception {
        UUID outroPlano = UUID.randomUUID();
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 150, 1)",
                outroPlano, "Plano " + outroPlano);
        try {
            for (int rodada = 0; rodada < RODADAS; rodada++) {
                UUID id = service.matricular(criarAluno(), plano, null).getId();

                List<String> resultados = aoMesmoTempo(2, i -> {
                    try {
                        service.trocarPlano(id, outroPlano, usuario);
                        return "trocada";
                    } catch (MatriculaRecusadaException e) {
                        return "409 " + e.titulo();
                    } catch (Exception e) {
                        return "outro erro: " + e.getClass().getSimpleName();
                    }
                });

                assertThat(resultados).as("rodada %d", rodada).containsExactlyInAnyOrder("trocada", "409 Troca recusada");
            }
        } finally {
            for (UUID aluno : alunos) {
                limpeza.apagarOnde("matriculas", "aluno_id = ?", aluno);
            }
            limpeza.apagar("planos", outroPlano);
        }
    }
}
