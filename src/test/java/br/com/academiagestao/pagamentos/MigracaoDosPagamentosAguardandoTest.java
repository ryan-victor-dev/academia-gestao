package br.com.academiagestao.pagamentos;

import br.com.academiagestao.alunos.CpfsParaTeste;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A V43 cria o índice de um Pix e um boleto aguardando por cobrança (ADR 0035). Os duplicados
 * de antes dela não podem impedir a migração: fica o mais novo, e os outros viram CANCELADO.
 * Banco próprio, com as migrações até a V42, os dados, e então a V43.
 */
class MigracaoDosPagamentosAguardandoTest {

    @Test
    void ficaOMaisNovoDeCadaCobrancaEForma() {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            var banco = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var flyway = Flyway.configure().dataSource(banco).locations("classpath:db/migration");
            flyway.target("42").load().migrate();
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
                    VALUES (?, ?, ?, 'P', 100, 3, current_date, (current_date + interval '3 months' - interval '1 day')::date, now())""", matricula, aluno, plano);
            UUID cobranca = cobranca(jdbc, matricula, aluno, 1);
            UUID outra = cobranca(jdbc, matricula, aluno, 2);

            UUID pixVelho = pagamento(jdbc, cobranca, aluno, usuario, "PIX", 3);
            UUID pixDoMeio = pagamento(jdbc, cobranca, aluno, usuario, "PIX", 2);
            UUID pixNovo = pagamento(jdbc, cobranca, aluno, usuario, "PIX", 1);
            UUID boleto = pagamento(jdbc, cobranca, aluno, usuario, "BOLETO", 3);
            UUID pixDaOutra = pagamento(jdbc, outra, aluno, usuario, "PIX", 3);

            flyway.target("43").load().migrate();

            assertThat(situacao(jdbc, pixVelho)).isEqualTo("CANCELADO");
            assertThat(situacao(jdbc, pixDoMeio)).isEqualTo("CANCELADO");
            assertThat(situacao(jdbc, pixNovo)).isEqualTo("AGUARDANDO");
            assertThat(situacao(jdbc, boleto)).as("outra forma").isEqualTo("AGUARDANDO");
            assertThat(situacao(jdbc, pixDaOutra)).as("outra cobrança").isEqualTo("AGUARDANDO");
        }
    }

    private static UUID cobranca(JdbcTemplate jdbc, UUID matricula, UUID aluno, int parcela) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, ?, 'MENSALIDADE', ?, 100, current_date, 'PENDENTE')""", id, matricula, aluno, parcela);
        return id;
    }

    /** Um aguardando gerado {@code horasAtras} horas antes de agora. */
    private static UUID pagamento(JdbcTemplate jdbc, UUID cobranca, UUID aluno, UUID usuario, String forma,
                                  int horasAtras) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pagamentos_online (id, cobranca_id, aluno_id, forma, provedor, id_externo, valor, acrescimo,
                                               juros, pix_copia_e_cola, boleto_linha_digitavel, valido_ate, gerado_em,
                                               gerado_por, situacao)
                VALUES (?, ?, ?, ?, 'simulado', ?, 100, 0, 0, CASE WHEN ? = 'PIX' THEN 'pix' END,
                        CASE WHEN ? = 'BOLETO' THEN 'linha' END, now() + interval '1 day',
                        now() - make_interval(hours => ?), ?, 'AGUARDANDO')""",
                id, cobranca, aluno, forma, "ext-" + id, forma, forma, horasAtras, usuario);
        return id;
    }

    private static String situacao(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT situacao FROM pagamentos_online WHERE id = ?", String.class, id);
    }
}
