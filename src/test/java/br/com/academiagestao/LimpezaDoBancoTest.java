package br.com.academiagestao;

import br.com.academiagestao.alunos.CpfsParaTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A limpeza central dos testes (R5) apaga tudo, na ordem das chaves estrangeiras. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LimpezaDoBancoTest {

    private static final List<String> TABELAS = List.of(
            "alunos", "planos", "usuarios", "matriculas", "trancamentos", "auditoria", "tentativas_login");

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    JdbcTemplate jdbc;

    /** Uma linha em cada tabela, todas ligadas: só sai na ordem certa. */
    private void preencherTudo() {
        UUID aluno = UUID.randomUUID();
        UUID plano = UUID.randomUUID();
        UUID usuario = UUID.randomUUID();
        UUID matricula = UUID.randomUUID();
        LocalDate hoje = LocalDate.now();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 1)",
                plano, "Plano " + plano);
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, aluno_id) VALUES (?, 'Aluno', ?, 'x', 'ALUNO', ?)",
                usuario, usuario + "@teste.local", aluno);
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim,
                                        criada_em, cancelada_em, cancelada_por, motivo_cancelamento, dias_trancados)
                VALUES (?, ?, ?, 'Mensal', 100, 1, ?, ?, now(), now(), ?, 'Teste', 10)""",
                matricula, aluno, plano, hoje, hoje.plusMonths(1).minusDays(1).plusDays(10), usuario);
        jdbc.update("""
                INSERT INTO trancamentos (id, matricula_id, inicio, volta_prevista, motivo, criado_por, criado_em)
                VALUES (?, ?, ?, ?, 'Viagem', ?, now())""", UUID.randomUUID(), matricula, hoje, hoje.plusDays(10), usuario);
        jdbc.update("INSERT INTO auditoria (entidade, entidade_id, acao, alteracoes) VALUES ('ALUNO', ?, 'TESTE', '{}')",
                aluno);
        jdbc.update("INSERT INTO tentativas_login (tipo, chave, ip, momento) VALUES ('CONTA', 'limpeza@teste.local', '203.0.113.9', now())");
    }

    private int linhas(String tabela) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabela, Integer.class);
    }

    @Test
    void apagaTodasAsTabelasMenosOHistoricoDoFlyway() {
        preencherTudo();
        int migracoes = linhas("flyway_schema_history");
        TABELAS.forEach(t -> assertThat(linhas(t)).as(t).isPositive());

        limpeza.apagarTudo();

        TABELAS.forEach(t -> assertThat(linhas(t)).as(t).isZero());
        assertThat(linhas("flyway_schema_history")).isEqualTo(migracoes);
    }

    @Test
    void filhasAntesDasMaes() {
        List<String> ordem = limpeza.ordemParaApagar();

        assertThat(ordem).containsExactlyInAnyOrderElementsOf(TABELAS);
        assertThat(ordem.indexOf("trancamentos")).isLessThan(ordem.indexOf("matriculas"));
        assertThat(ordem.indexOf("matriculas"))
                .isLessThan(ordem.indexOf("usuarios"))
                .isLessThan(ordem.indexOf("planos"))
                .isLessThan(ordem.indexOf("alunos"));
        assertThat(ordem.indexOf("usuarios")).isLessThan(ordem.indexOf("alunos"));
    }

    /**
     * O motivo do R5: uma tabela nova entra sem mexer em nenhum teste. Esta aponta também para
     * ela mesma, o que não pode travar a ordem (um DELETE só apaga as duas linhas).
     */
    @Test
    void tabelaNovaEntraSozinha() {
        jdbc.execute("""
                CREATE TABLE teste_tabela_nova (id INT PRIMARY KEY, usuario_id UUID NOT NULL REFERENCES usuarios (id),
                                                anterior INT REFERENCES teste_tabela_nova (id))""");
        try {
            preencherTudo();
            jdbc.update("INSERT INTO teste_tabela_nova SELECT 1, id, NULL FROM usuarios LIMIT 1");
            jdbc.update("INSERT INTO teste_tabela_nova SELECT 2, id, 1 FROM usuarios LIMIT 1");

            limpeza.apagarTudo();

            assertThat(linhas("teste_tabela_nova")).isZero();
            assertThat(linhas("usuarios")).isZero();
        } finally {
            jdbc.execute("DROP TABLE teste_tabela_nova");
        }
    }
}
