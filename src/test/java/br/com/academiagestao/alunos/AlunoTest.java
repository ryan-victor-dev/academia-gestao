package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

import static br.com.academiagestao.alunos.NascimentoParaTeste.NASCIMENTO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlunoTest {

    private static DadosAluno dados(String nome, String cpf) {
        return new DadosAluno(nome, cpf, null, null, NASCIMENTO, null);
    }

    @Test
    void novoAlunoNasceAtivoComDadosNormalizados() {
        Aluno aluno = Aluno.novo(new DadosAluno("  Ana Souza ", "529.982.247-25", " Ana@Email.COM ",
                "(31) 98765-4321", LocalDate.of(1995, 3, 10), "  "), Instant.now());

        assertThat(aluno.getId()).isNotNull();
        assertThat(aluno.isAtivo()).isTrue();
        assertThat(aluno.getNome()).isEqualTo("Ana Souza");
        assertThat(aluno.getCpf()).isEqualTo("52998224725");
        assertThat(aluno.getEmail()).isEqualTo("ana@email.com");
        assertThat(aluno.getTelefone()).isEqualTo("31987654321");
        assertThat(aluno.getObservacoes()).isNull(); // só espaços conta como vazio
        assertThat(aluno.getCriadoEm().getNano() % 1000).isZero(); // mesma precisão do Postgres
    }

    @Test
    void recusaCpfInvalido() {
        assertThatThrownBy(() -> Aluno.novo(dados("Ana", "111.111.111-11"), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("CPF");
    }

    @Test
    void recusaNomeVazio() {
        assertThatThrownBy(() -> Aluno.novo(dados("   ", "52998224725"), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("nome");
    }

    @Test
    void recusaTelefoneSemDdd() {
        assertThatThrownBy(() -> Aluno.novo(new DadosAluno("Ana", "52998224725", null, "98765-4321", NASCIMENTO, null), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("telefone");
    }

    /** ADR 0048: obrigatória no cadastro novo; quem tem não apaga; quem é de antes continua editável sem. */
    @Test
    void nascimentoObrigatorioNoNovoEQueNaoSeApaga() {
        assertThatThrownBy(() -> Aluno.novo(new DadosAluno("Ana", "52998224725", null, null, null, null), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessage("data de nascimento é obrigatória");

        Aluno comData = Aluno.novo(dados("Ana", "52998224725"), Instant.now());
        assertThatThrownBy(() -> comData.atualizar(new DadosAluno("Ana Maria", "52998224725", null, null, null, null), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessage("data de nascimento é obrigatória");
        assertThat(comData.getNome()).isEqualTo("Ana");
        assertThat(comData.getDataNascimento()).isEqualTo(NASCIMENTO);

        Aluno deAntes = Aluno.novo(dados("Bia", "11144477735"), Instant.now());
        ReflectionTestUtils.setField(deAntes, "dataNascimento", null);
        deAntes.atualizar(new DadosAluno("Bia Lima", "11144477735", null, null, null, null), Instant.now());
        assertThat(deAntes.getNome()).isEqualTo("Bia Lima");
        assertThat(deAntes.getDataNascimento()).isNull();
    }

    // 19/10/2026, 23h30 em São Paulo: em UTC já é dia 20.
    private static final Instant NOITE = Instant.parse("2026-10-20T02:30:00Z");
    private static final LocalDate HOJE_NA_ACADEMIA = LocalDate.of(2026, 10, 19);

    private static DadosAluno nascidoEm(LocalDate nascimento) {
        return new DadosAluno("Ana", "52998224725", null, null, nascimento, null);
    }

    @Test
    void recusaNascimentoNoFuturo() {
        assertThat(Aluno.novo(nascidoEm(HOJE_NA_ACADEMIA), NOITE).getDataNascimento()).isEqualTo(HOJE_NA_ACADEMIA);
        assertThatThrownBy(() -> Aluno.novo(nascidoEm(HOJE_NA_ACADEMIA.plusDays(1)), NOITE))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("futuro");
    }

    /** O "hoje" é o da academia: às 23h30 de São Paulo, o dia seguinte (o de UTC) ainda é futuro. */
    @Test
    void naEdicaoTambemOHojeEhODaAcademia() {
        Aluno aluno = Aluno.novo(nascidoEm(NASCIMENTO), NOITE);

        assertThatThrownBy(() -> aluno.atualizar(nascidoEm(HOJE_NA_ACADEMIA.plusDays(1)), NOITE))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("futuro");
    }

    @Test
    void nascimentoDeAte120AnosAtras() {
        LocalDate limite = HOJE_NA_ACADEMIA.minusYears(120);
        assertThat(Aluno.novo(nascidoEm(limite), NOITE).getDataNascimento()).isEqualTo(limite);
        assertThatThrownBy(() -> Aluno.novo(nascidoEm(limite.minusDays(1)), NOITE))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("120 anos");
        assertThatThrownBy(() -> Aluno.novo(nascidoEm(LocalDate.MIN), NOITE))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("120 anos");
    }

    @Test
    void criadoEmVemDoRelogio() {
        assertThat(Aluno.novo(nascidoEm(NASCIMENTO), NOITE).getCriadoEm()).isEqualTo(NOITE);
    }

    @Test
    void edicaoInvalidaNaoAlteraNada() {
        Aluno aluno = Aluno.novo(dados("Ana", "52998224725"), Instant.now());

        assertThatThrownBy(() -> aluno.atualizar(dados("Ana Maria", "123"), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class);

        // O nome era válido, mas o CPF não: nada pode ter mudado.
        assertThat(aluno.getNome()).isEqualTo("Ana");
        assertThat(aluno.getCpf()).isEqualTo("52998224725");
    }

    @Test
    void inativarEReativar() {
        Aluno aluno = Aluno.novo(dados("Ana", "52998224725"), Instant.now());

        aluno.inativar();
        aluno.inativar(); // de novo não quebra
        assertThat(aluno.isAtivo()).isFalse();

        aluno.reativar();
        assertThat(aluno.isAtivo()).isTrue();
    }

    @Test
    void recusaDadosNulos() {
        assertThatThrownBy(() -> Aluno.novo(null, Instant.now())).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void nomeAte150CaracteresEAcima() {
        assertThat(Aluno.novo(dados("a".repeat(150), "52998224725"), Instant.now()).getNome()).hasSize(150);
        assertThatThrownBy(() -> Aluno.novo(dados("a".repeat(151), "52998224725"), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("150");
    }

    @Test
    void emailSemArrobaOuComprido() {
        assertThatThrownBy(() -> Aluno.novo(new DadosAluno("Ana", "52998224725", "ana.email.com", null, NASCIMENTO, null), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("e-mail");
        String email150 = "a".repeat(140) + "@email.com";
        assertThat(Aluno.novo(new DadosAluno("Ana", "52998224725", email150, null, NASCIMENTO, null), Instant.now()).getEmail())
                .hasSize(150);
        assertThatThrownBy(() -> Aluno.novo(new DadosAluno("Ana", "52998224725", "a" + email150, null, NASCIMENTO, null), Instant.now()))
                .isInstanceOf(DadosInvalidosException.class);
    }

    /** O aluno corrige o e-mail no primeiro acesso (ADR 0038): o cadastro acompanha, normalizado. */
    @Test
    void corrigeOEmailNormalizadoERecusaOInvalido() {
        Aluno aluno = Aluno.novo(dados("Ana", "52998224725"), Instant.now());

        aluno.corrigirEmail("  Ana@Email.COM ");
        assertThat(aluno.getEmail()).isEqualTo("ana@email.com");

        String noLimite = "a".repeat(Aluno.TAMANHO_MAXIMO_EMAIL - "@x.com".length()) + "@x.com";
        aluno.corrigirEmail(noLimite);
        assertThat(aluno.getEmail()).isEqualTo(noLimite);
        for (String invalido : new String[] {null, "  ", "sem-arroba", "a" + noLimite}) {
            assertThatThrownBy(() -> aluno.corrigirEmail(invalido)).as(invalido)
                    .isInstanceOf(DadosInvalidosException.class);
        }
        assertThat(aluno.getEmail()).isEqualTo(noLimite);
    }
}
