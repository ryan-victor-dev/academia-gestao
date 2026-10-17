package br.com.academiagestao.matriculas;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * As regras que moram no banco (ADR 0016): valem também numa corrida entre dois cadastros
 * e para um INSERT feito fora da aplicação.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MatriculaRepositoryTest {

    @Autowired
    MatriculaRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private UUID aluno;
    private UUID outroAluno;
    private UUID plano;
    private UUID usuario;

    @BeforeEach
    void preparar() {
        aluno = criarAluno();
        outroAluno = criarAluno();
        plano = UUID.randomUUID();
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 129.90, 1)",
                plano, "Plano " + plano);
        usuario = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Recepção', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
    }

    // Outros testes apagam planos, alunos e usuários; as matrículas que apontam para eles
    // precisam sair antes, senão a chave estrangeira quebra esses testes.
    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM matriculas WHERE aluno_id IN (?, ?)", aluno, outroAluno);
        jdbc.update("DELETE FROM usuarios WHERE id = ?", usuario);
        jdbc.update("DELETE FROM planos WHERE id = ?", plano);
        jdbc.update("DELETE FROM alunos WHERE id IN (?, ?)", aluno, outroAluno);
    }

    private UUID criarAluno() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno de teste', ?)", id, CpfsParaTeste.aleatorio());
        return id;
    }

    private static Instant em(String data) {
        return LocalDateTime.parse(data + "T10:00").atZone(Matricula.FUSO).toInstant();
    }

    private Matricula matricula(UUID alunoId, String inicio, int meses) {
        return Matricula.nova(alunoId, new PlanoContratado(plano, "Mensal", new BigDecimal("129.90"), meses),
                LocalDate.parse(inicio), em(inicio));
    }

    @Test
    void salvaELeDeVolta() {
        Matricula salva = repository.saveAndFlush(matricula(aluno, "2027-01-31", 1));

        Matricula lida = repository.findById(salva.getId()).orElseThrow();
        assertThat(lida.getValorMensal()).isEqualByComparingTo("129.90");
        assertThat(lida.getPlanoNome()).isEqualTo("Mensal");
        assertThat(lida.getInicio()).isEqualTo(LocalDate.parse("2027-01-31"));
        assertThat(lida.getFim()).isEqualTo(LocalDate.parse("2027-02-27"));
        assertThat(lida.getCriadaEm()).isEqualTo(salva.getCriadaEm());
        assertThat(lida.getVersao()).isZero();
    }

    @Test
    void cancelamentoEhGravado() {
        Matricula matricula = repository.saveAndFlush(matricula(aluno, "2027-01-10", 1));
        matricula.cancelar(usuario, "Mudou de cidade", em("2027-01-15"));
        repository.saveAndFlush(matricula);

        Matricula lida = repository.findById(matricula.getId()).orElseThrow();
        assertThat(lida.getCanceladaPor()).isEqualTo(usuario);
        assertThat(lida.getMotivoCancelamento()).isEqualTo("Mudou de cidade");
        assertThat(lida.situacao(em("2027-01-16"))).isEqualTo(SituacaoMatricula.CANCELADA);
        assertThat(lida.getVersao()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
            "2027-01-10, 1",  // mesmo período
            "2027-01-20, 1",  // começa no meio da atual
            "2027-02-09, 1",  // começa no último dia da atual (fim é inclusivo)
            "2026-12-15, 1",  // termina no meio da atual
            "2026-12-01, 3",  // engloba a atual
    })
    void bancoRecusaPeriodosSobrepostosDoMesmoAluno(String inicio, int meses) {
        repository.saveAndFlush(matricula(aluno, "2027-01-10", 1));  // 10/01 a 09/02

        assertThatThrownBy(() -> repository.saveAndFlush(matricula(aluno, inicio, meses)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_matriculas_periodo_sobreposto");
    }

    @Test
    void renovacaoNoDiaSeguinteAoFimEhAceita() {
        Matricula atual = repository.saveAndFlush(matricula(aluno, "2027-01-31", 1));

        assertThatCode(() -> repository.saveAndFlush(
                matricula(aluno, atual.getFim().plusDays(1).toString(), 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void outroAlunoNoMesmoPeriodoEhAceito() {
        repository.saveAndFlush(matricula(aluno, "2027-01-10", 1));

        assertThatCode(() -> repository.saveAndFlush(matricula(outroAluno, "2027-01-10", 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void canceladaNaoImpedeNovaMatriculaNoMesmoPeriodo() {
        Matricula cancelada = repository.saveAndFlush(matricula(aluno, "2027-01-10", 1));
        cancelada.cancelar(usuario, "Trocou de plano", em("2027-01-10"));
        repository.saveAndFlush(cancelada);

        assertThatCode(() -> repository.saveAndFlush(matricula(aluno, "2027-01-10", 1)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "2027-01-31, 1, 2027-02-28",  // o certo é 27/02
            "2026-10-17, 1, 2026-11-17",  // esqueceu o "- 1 dia"
            "2026-10-17, 3, 2026-11-16",  // fim de um plano mensal com duração trimestral
    })
    void bancoRecusaFimQueNaoBateComADuracao(String inicio, int meses, String fim) {
        assertThatThrownBy(() -> inserirDireto(inicio, meses, fim))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_matriculas_fim");
    }

    @ParameterizedTest
    @CsvSource({
            "2027-01-31, 1, 2027-02-27",
            "2028-01-31, 1, 2028-02-28",
            "2026-10-17, 60, 2031-10-16",
    })
    void contaDoBancoBateComADoJava(String inicio, int meses, String fim) {
        assertThat(Matricula.fimDoPeriodo(LocalDate.parse(inicio), meses)).isEqualTo(LocalDate.parse(fim));
        assertThatCode(() -> inserirDireto(inicio, meses, fim)).doesNotThrowAnyException();
    }

    @Test
    void bancoRecusaCancelamentoIncompletoOuSemMotivo() {
        UUID id = repository.saveAndFlush(matricula(aluno, "2027-01-10", 1)).getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE matriculas SET cancelada_em = now() WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_matriculas_cancelamento");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE matriculas SET cancelada_em = now(), cancelada_por = ? WHERE id = ?", usuario, id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_matriculas_cancelamento");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE matriculas SET motivo_cancelamento = 'Sem data nem autor' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_matriculas_cancelamento");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE matriculas SET cancelada_em = now(), motivo_cancelamento = 'Sem autor' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_matriculas_cancelamento");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE matriculas SET cancelada_em = now(), cancelada_por = ?, motivo_cancelamento = '  ' WHERE id = ?",
                usuario, id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_matriculas_motivo");
    }

    @Test
    void bancoRecusaNomeDoPlanoEmBranco() {
        UUID id = repository.saveAndFlush(matricula(aluno, "2027-01-10", 1)).getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE matriculas SET plano_nome = '  ' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_matriculas_plano_nome");
        assertThatThrownBy(() -> jdbc.update("UPDATE matriculas SET plano_nome = NULL WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void inserirDireto(String inicio, int meses, String fim) {
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'Mensal', 129.90, ?, ?, ?, now())""",
                UUID.randomUUID(), aluno, plano, meses,
                Date.valueOf(LocalDate.parse(inicio)), Date.valueOf(LocalDate.parse(fim)));
    }
}
