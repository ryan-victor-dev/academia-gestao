package br.com.academiagestao.matriculas;

import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Inativar um aluno com matrícula vigente ou agendada é recusado: cancele antes (ADR 0016). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InativacaoComMatriculaTest {

    private static final RequestPostProcessor RECEPCAO =
            jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("perfil", "RECEPCAO"))
                    .authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired
    MockMvc mvc;

    @Autowired
    MatriculaService matriculas;

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
            jdbc.update("DELETE FROM trancamentos WHERE matricula_id IN (SELECT id FROM matriculas WHERE aluno_id = ?)",
                    id);
            jdbc.update("DELETE FROM matriculas WHERE aluno_id = ?", id);
            jdbc.update("DELETE FROM alunos WHERE id = ?", id);
        }
        alunos.clear();
        jdbc.update("DELETE FROM usuarios WHERE id = ?", usuario);
        jdbc.update("DELETE FROM planos WHERE id = ?", plano);
    }

    private UUID criarAluno() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", id, CpfsParaTeste.aleatorio());
        alunos.add(id);
        return id;
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), Matricula.FUSO);
    }

    /** Grava direto: matrículas no passado não passam pela API. */
    private void matricula(UUID aluno, LocalDate inicio, boolean cancelada) {
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim,
                                        criada_em, cancelada_em, cancelada_por, motivo_cancelamento)
                VALUES (?, ?, ?, 'Mensal', 100, 1, ?, ?, now(), ?, ?, ?)""",
                UUID.randomUUID(), aluno, plano, Date.valueOf(inicio), Date.valueOf(inicio.plusMonths(1).minusDays(1)),
                cancelada ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
                cancelada ? usuario : null, cancelada ? "Desistiu" : null);
    }

    private ResultActions inativar(UUID aluno) throws Exception {
        return mvc.perform(post("/api/alunos/" + aluno + "/inativar").with(RECEPCAO));
    }

    private boolean ativo(UUID aluno) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ativo FROM alunos WHERE id = ?", Boolean.class, aluno));
    }

    @Test
    void comMatriculaVigenteEhRecusado() throws Exception {
        UUID aluno = criarAluno();
        matricula(aluno, hoje(), false);

        inativar(aluno)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Aluno não pode ser inativado"))
                .andExpect(jsonPath("$.detail").value(containsString(
                        "de " + hoje().format(DATA) + " a " + hoje().plusMonths(1).minusDays(1).format(DATA))))
                .andExpect(jsonPath("$.detail").value(containsString("cancele")));
        assertThat(ativo(aluno)).isTrue();
    }

    @Test
    void terminandoHojeAindaEhVigente() throws Exception {
        UUID aluno = criarAluno();
        matricula(aluno, hoje().minusMonths(1).plusDays(1), false);  // fim = hoje

        inativar(aluno).andExpect(status().isConflict());
    }

    @Test
    void comMatriculaAgendadaEhRecusado() throws Exception {
        UUID aluno = criarAluno();
        matricula(aluno, hoje().plusDays(10), false);

        inativar(aluno).andExpect(status().isConflict());
        assertThat(ativo(aluno)).isTrue();
    }

    @Test
    void comMatriculaEncerradaOuCanceladaPode() throws Exception {
        UUID aluno = criarAluno();
        matricula(aluno, hoje().minusMonths(1), false);  // fim = ontem
        matricula(aluno, hoje(), true);
        matricula(aluno, hoje().plusMonths(2), true);

        inativar(aluno).andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false));
        assertThat(ativo(aluno)).isFalse();
    }

    @Test
    void semMatriculaPode() throws Exception {
        UUID aluno = criarAluno();

        inativar(aluno).andExpect(status().isOk());
    }

    @Test
    void depoisDeCancelarPode() throws Exception {
        UUID aluno = criarAluno();
        UUID id = matriculas.matricular(aluno, plano, null).getId();
        inativar(aluno).andExpect(status().isConflict());

        matriculas.cancelar(id, "Vai se mudar", usuario);

        inativar(aluno).andExpect(status().isOk());
    }

    /**
     * Matricular, "pode treinar?" e a inativação tiram o "hoje" do relógio único (ADR 0023):
     * com ele 400 dias à frente, a matrícula começa nesse dia, vale nele e, passado o fim,
     * deixa inativar. Com o relógio do sistema, ela ficaria agendada e a inativação, recusada.
     */
    @Test
    void hojeVemDoRelogioDaAplicacao() throws Exception {
        relogio.adiantar(Duration.ofDays(400));
        UUID aluno = criarAluno();

        Matricula matricula = matriculas.matricular(aluno, plano, null);

        assertThat(matricula.getInicio()).isEqualTo(LocalDate.ofInstant(relogio.instant(), Matricula.FUSO));
        assertThat(matriculas.temMatriculaVigente(aluno)).isTrue();
        inativar(aluno).andExpect(status().isConflict());

        relogio.adiantar(Duration.ofDays(40));

        assertThat(matriculas.temMatriculaVigente(aluno)).isFalse();
        inativar(aluno).andExpect(status().isOk());
    }

    @Test
    void alunoInexistenteContinuaDando404() throws Exception {
        inativar(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    /**
     * Inativar e matricular ao mesmo tempo: as duas operações travam a linha do aluno antes
     * de conferir, então uma sempre vê o resultado da outra. Nunca pode sobrar um aluno
     * inativo com matrícula valendo. Várias rodadas: uma só poderia passar por sorte.
     */
    @Test
    void inativarEMatricularAoMesmoTempo() throws Exception {
        int inativados = 0;
        int matriculados = 0;
        for (int rodada = 0; rodada < 30; rodada++) {
            UUID aluno = criarAluno();
            CountDownLatch largada = new CountDownLatch(1);
            CompletableFuture<Integer> inativacao = CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    return inativar(aluno).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    return -1;
                }
            });
            // A inativação passa pelo MockMvc e chega sempre depois; um atraso aleatório na
            // matrícula faz cada lado ganhar em parte das rodadas.
            long atraso = ThreadLocalRandom.current().nextLong(4);
            CompletableFuture<String> matricula = CompletableFuture.supplyAsync(() -> {
                try {
                    largada.await();
                    Thread.sleep(atraso);
                    matriculas.matricular(aluno, plano, null);
                    return "criada";
                } catch (MatriculaRecusadaException e) {
                    return "409 " + e.titulo();
                } catch (Exception e) {
                    return "outro erro: " + e.getClass().getSimpleName();
                }
            });
            largada.countDown();
            int statusInativacao = inativacao.get(20, TimeUnit.SECONDS);
            String resultadoMatricula = matricula.get(20, TimeUnit.SECONDS);

            boolean ativo = ativo(aluno);
            int valendo = jdbc.queryForObject(
                    "SELECT count(*) FROM matriculas WHERE aluno_id = ? AND cancelada_em IS NULL", Integer.class, aluno);
            if (statusInativacao == 200) {
                inativados++;
                assertThat(resultadoMatricula).as("rodada %d", rodada).isEqualTo("409 Aluno inativo");
                assertThat(ativo).isFalse();
                assertThat(valendo).as("rodada %d: inativo com matrícula", rodada).isZero();
            } else {
                matriculados++;
                assertThat(statusInativacao).as("rodada %d", rodada).isEqualTo(409);
                assertThat(resultadoMatricula).isEqualTo("criada");
                assertThat(ativo).isTrue();
                assertThat(valendo).isEqualTo(1);
            }
        }
        // Registra quantas vezes cada lado ganhou: se um lado nunca ganhasse, a corrida não teria
        // sido testada de verdade.
        System.out.printf("inativarEMatricularAoMesmoTempo: %d inativados, %d matriculados%n", inativados, matriculados);
    }
}
