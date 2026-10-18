package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revisão de 2026-10-17 (M6): busca e listagem de alunos varriam a tabela inteira. Com
 * poucas linhas o Postgres prefere varrer mesmo com índice; aqui os outros caminhos são
 * desligados (só nesta transação) e o teste confere, pelas estatísticas do banco, que a
 * consulta que a aplicação gera usa o índice. Se a expressão da busca deixar de bater com
 * a do índice, ele deixa de ser usado e o teste falha.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IndicesDaBuscaDeAlunosTest {

    @Autowired
    AlunoRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transacoes;

    private long usosDo(String indice) {
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return jdbc.queryForObject("SELECT idx_scan FROM pg_stat_user_indexes WHERE indexrelname = ?",
                Long.class, indice);
    }

    /** Roda a listagem como o AlunoService, com os caminhos dados desligados. */
    private long usosDuranteAListagem(String indice, Specification<Aluno> filtro, String... desligados) {
        long antes = usosDo(indice);
        new TransactionTemplate(transacoes).executeWithoutResult(s -> {
            for (String caminho : desligados) {
                jdbc.execute("SET LOCAL " + caminho + " = off");
            }
            repository.findAll(filtro, PageRequest.of(0, 20, Sort.by("nome", "id")));
            // Grava as estatísticas desta conexão ao fim da transação, sem esperar.
            jdbc.execute("SELECT pg_stat_force_next_flush()");
        });
        return usosDo(indice) - antes;
    }

    // Na busca, só sobra a leitura por bitmap, que exige uma condição que o índice atenda.
    // (Desligar só a varredura não basta: o Postgres percorreria o índice de nome em ordem.)
    private static final String[] SO_BITMAP = {"enable_seqscan", "enable_indexscan", "enable_indexonlyscan"};

    @Test
    void buscaPorParteDoNomeUsaOIndiceDeTrigramas() {
        assertThat(usosDuranteAListagem("ix_alunos_busca_nome", AlunoRepository.filtro("José", false, true),
                SO_BITMAP)).isPositive();
    }

    @Test
    void buscaPeloComecoDoCpfUsaOIndiceDePrefixo() {
        assertThat(usosDuranteAListagem("ix_alunos_cpf_prefixo", AlunoRepository.filtro("529.982", false, true),
                SO_BITMAP)).isPositive();
    }

    @Test
    void listagemEmOrdemDeNomeUsaOIndiceDeNome() {
        assertThat(usosDuranteAListagem("ix_alunos_nome_id", AlunoRepository.filtro(null, false, true),
                "enable_seqscan", "enable_sort")).isPositive();
    }
}
