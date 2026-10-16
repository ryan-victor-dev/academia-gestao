package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlunoTest {

    private static DadosAluno dados(String nome, String cpf) {
        return new DadosAluno(nome, cpf, null, null, null, null);
    }

    @Test
    void novoAlunoNasceAtivoComDadosNormalizados() {
        Aluno aluno = Aluno.novo(new DadosAluno("  Ana Souza ", "529.982.247-25", " Ana@Email.COM ",
                "(31) 98765-4321", LocalDate.of(1995, 3, 10), "  "));

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
        assertThatThrownBy(() -> Aluno.novo(dados("Ana", "111.111.111-11")))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("CPF");
    }

    @Test
    void recusaNomeVazio() {
        assertThatThrownBy(() -> Aluno.novo(dados("   ", "52998224725")))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("nome");
    }

    @Test
    void recusaTelefoneSemDdd() {
        assertThatThrownBy(() -> Aluno.novo(new DadosAluno("Ana", "52998224725", null, "98765-4321", null, null)))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("telefone");
    }

    @Test
    void recusaNascimentoNoFuturo() {
        assertThatThrownBy(() -> Aluno.novo(new DadosAluno("Ana", "52998224725", null, null,
                LocalDate.now().plusDays(1), null)))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageContaining("futuro");
    }

    @Test
    void edicaoInvalidaNaoAlteraNada() {
        Aluno aluno = Aluno.novo(dados("Ana", "52998224725"));

        assertThatThrownBy(() -> aluno.atualizar(dados("Ana Maria", "123")))
                .isInstanceOf(DadosInvalidosException.class);

        // O nome era válido, mas o CPF não: nada pode ter mudado.
        assertThat(aluno.getNome()).isEqualTo("Ana");
        assertThat(aluno.getCpf()).isEqualTo("52998224725");
    }

    @Test
    void inativarEReativar() {
        Aluno aluno = Aluno.novo(dados("Ana", "52998224725"));

        aluno.inativar();
        aluno.inativar(); // de novo não quebra
        assertThat(aluno.isAtivo()).isFalse();

        aluno.reativar();
        assertThat(aluno.isAtivo()).isTrue();
    }
}
