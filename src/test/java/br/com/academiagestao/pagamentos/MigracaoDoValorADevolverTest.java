package br.com.academiagestao.pagamentos;

import br.com.academiagestao.alunos.CpfsParaTeste;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A V44 cria o valor a devolver (ADR 0035). O que já estava a devolver ou devolvido, antes dela,
 * era sempre tudo o que chegou: o valor a devolver é o recebido. Banco próprio, com as migrações
 * até a V43, os dados, e então a V44.
 */
class MigracaoDoValorADevolverTest {

    @Test
    void oQueJaEraADevolverDevolveTudo() {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            var banco = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var flyway = Flyway.configure().dataSource(banco).locations("classpath:db/migration");
            flyway.target("43").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(banco);

            UUID usuario = UUID.randomUUID();
            jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'A', 'a@a.com', 'x', 'ADMIN')",
                    usuario);
            UUID aluno = UUID.randomUUID();
            jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
            UUID plano = UUID.randomUUID();
            jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, 'P', 100, 3)", plano);
            UUID matricula = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim,
                                            criada_em)
                    VALUES (?, ?, ?, 'P', 100, 3, current_date, (current_date + interval '3 months' - interval '1 day')::date,
                            now())""", matricula, aluno, plano);
            UUID cobranca = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                    VALUES (?, ?, ?, 'MENSALIDADE', 1, 100, current_date, 'PENDENTE')""", cobranca, matricula, aluno);

            UUID aDevolver = pagamento(jdbc, cobranca, aluno, usuario, "A_DEVOLVER");
            UUID devolvido = pagamento(jdbc, cobranca, aluno, usuario, "DEVOLVIDO");
            UUID aguardando = pagamento(jdbc, cobranca, aluno, usuario, "AGUARDANDO");

            flyway.target("44").load().migrate();

            assertThat(valorADevolver(jdbc, aDevolver)).isEqualByComparingTo("80.00");
            assertThat(valorADevolver(jdbc, devolvido)).isEqualByComparingTo("80.00");
            assertThat(valorADevolver(jdbc, aguardando)).isNull();
        }
    }

    /** Um Pix na situação dada; recebido 80,00 quando a situação exige. */
    private static UUID pagamento(JdbcTemplate jdbc, UUID cobranca, UUID aluno, UUID usuario, String situacao) {
        UUID id = UUID.randomUUID();
        boolean recebeu = !situacao.equals("AGUARDANDO");
        jdbc.update("""
                INSERT INTO pagamentos_online (id, cobranca_id, aluno_id, forma, provedor, id_externo, valor, acrescimo,
                                               juros, pix_copia_e_cola, valido_ate, gerado_em, gerado_por, situacao,
                                               recebido, recebido_em, motivo_da_devolucao, devolvido_em, devolvido_por)
                VALUES (?, ?, ?, 'PIX', 'simulado', ?, 100, 0, 0, 'pix', now() + interval '1 day', now(), ?, ?,
                        CASE WHEN ? THEN 80 END, CASE WHEN ? THEN now() END, CASE WHEN ? THEN 'veio menos' END,
                        CASE WHEN ? = 'DEVOLVIDO' THEN now() END, CASE WHEN ? = 'DEVOLVIDO' THEN ?::uuid END)""",
                id, cobranca, aluno, "ext-" + id, usuario, situacao, recebeu, recebeu, recebeu, situacao, situacao,
                usuario.toString());
        return id;
    }

    private static BigDecimal valorADevolver(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT valor_a_devolver FROM pagamentos_online WHERE id = ?", BigDecimal.class, id);
    }
}
