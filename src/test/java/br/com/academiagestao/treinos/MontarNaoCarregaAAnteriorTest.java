package br.com.academiagestao.treinos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Montar uma ficha nova encerra a anterior sem carregá-la (diagnóstico da etapa 7, B6;
 * pendências baixas, item 23): as divisões e os exercícios dela são EAGER, e só o momento do
 * encerramento muda. O resultado já era o mesmo; só a contagem do Hibernate mostra a diferença.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MontarNaoCarregaAAnteriorTest {

    @Autowired
    FichaService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    EntityManagerFactory emf;

    private final UUID aluno = UUID.randomUUID();
    private final UUID usuario = UUID.randomUUID();
    private final UUID exercicio = UUID.randomUUID();
    private final UUID anterior = UUID.randomUUID();

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'FICHA' AND entidade_id IN "
                + "(SELECT id FROM fichas WHERE aluno_id = ?)", aluno);
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("usuarios", usuario);
        limpeza.apagar("exercicios", exercicio);
    }

    @Test
    void encerraAAnteriorSemLerAsDivisoesNemOsExercicios() {
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'I', ?, 'x', 'INSTRUTOR')",
                usuario, usuario + "@academia.com");
        jdbc.update("INSERT INTO exercicios (id, nome, grupo_muscular, criado_em) VALUES (?, ?, 'PEITO', now())",
                exercicio, "Supino " + exercicio);
        UUID divisao = UUID.randomUUID();
        jdbc.update("INSERT INTO fichas (id, aluno_id, montada_em, montada_por) VALUES (?, ?, now() - interval '1 day', ?)",
                anterior, aluno, usuario);
        jdbc.update("INSERT INTO ficha_divisoes (id, ficha_id, posicao, nome) VALUES (?, ?, 1, 'A')", divisao, anterior);
        for (int e = 1; e <= 2; e++) {
            jdbc.update("""
                    INSERT INTO ficha_exercicios (id, divisao_id, posicao, exercicio_id, nome, series, repeticoes)
                    VALUES (?, ?, ?, ?, 'Supino', 3, '10')""", UUID.randomUUID(), divisao, e, exercicio);
        }
        Statistics estatisticas = emf.unwrap(SessionFactory.class).getStatistics();
        estatisticas.clear();

        Ficha nova = service.montar(aluno, new DadosFicha(null, List.of(new DadosFicha.Divisao("B",
                List.of(new DadosFicha.Item(exercicio, 4, "8", null, null, null))))), usuario);

        assertThat(estatisticas.getEntityStatistics(Ficha.class.getName()).getLoadCount()).isZero();
        assertThat(estatisticas.getEntityStatistics(DivisaoDaFicha.class.getName()).getLoadCount()).isZero();
        assertThat(estatisticas.getEntityStatistics(ExercicioDaFicha.class.getName()).getLoadCount()).isZero();

        // E o resultado de antes: a anterior encerrada (com versão nova), uma só atual, e na trilha
        // só o momento do encerramento.
        Map<String, Object> encerrada = jdbc.queryForMap("SELECT encerrada_em, versao FROM fichas WHERE id = ?", anterior);
        assertThat(encerrada.get("encerrada_em")).isNotNull();
        assertThat(((Timestamp) encerrada.get("encerrada_em")).toInstant()).isEqualTo(nova.getMontadaEm());
        assertThat(encerrada.get("versao")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fichas WHERE aluno_id = ? AND encerrada_em IS NULL",
                Integer.class, aluno)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT alteracoes::text FROM auditoria WHERE entidade = 'FICHA' "
                + "AND entidade_id = ? AND acao = 'ENCERRADA'", String.class, anterior))
                .isEqualTo("{\"encerradaEm\": {\"antes\": null, \"depois\": \"%s\"}}".formatted(nova.getMontadaEm()));
    }
}
