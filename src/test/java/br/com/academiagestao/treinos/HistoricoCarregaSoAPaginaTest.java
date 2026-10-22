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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uma página do histórico carrega só as divisões e os exercícios das fichas dela (diagnóstico
 * da etapa 7, T3). Com o SUBSELECT de antes, o resultado era o mesmo, mas o Hibernate lia o
 * histórico inteiro do aluno para montar cada página: só a contagem do Hibernate mostra.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class HistoricoCarregaSoAPaginaTest {

    private static final int FICHAS = 5;

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

    @AfterEach
    void limpar() {
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("usuarios", usuario);
        limpeza.apagar("exercicios", exercicio);
    }

    @Test
    void paginaDeDuasFichasLeDuasDivisoesEQuatroExercicios() {
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'I', ?, 'x', 'INSTRUTOR')",
                usuario, usuario + "@academia.com");
        jdbc.update("INSERT INTO exercicios (id, nome, grupo_muscular, criado_em) VALUES (?, ?, 'PEITO', now())",
                exercicio, "Supino " + exercicio);
        // Fichas encerradas, cada uma com uma divisão de dois exercícios (encerrada depois de
        // preenchida: o banco não deixa acrescentar nada a uma encerrada).
        for (int i = 0; i < FICHAS; i++) {
            UUID ficha = UUID.randomUUID();
            UUID divisao = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fichas (id, aluno_id, montada_em, montada_por)
                    VALUES (?, ?, now() - ? * interval '1 day', ?)""", ficha, aluno, i + 1, usuario);
            jdbc.update("INSERT INTO ficha_divisoes (id, ficha_id, posicao, nome) VALUES (?, ?, 1, 'A')", divisao, ficha);
            for (int e = 1; e <= 2; e++) {
                jdbc.update("""
                        INSERT INTO ficha_exercicios (id, divisao_id, posicao, exercicio_id, nome, series, repeticoes)
                        VALUES (?, ?, ?, ?, 'Supino', 3, '10')""", UUID.randomUUID(), divisao, e, exercicio);
            }
            jdbc.update("UPDATE fichas SET encerrada_em = now() WHERE id = ?", ficha);
        }
        Statistics estatisticas = emf.unwrap(SessionFactory.class).getStatistics();
        estatisticas.clear();

        // Duas na página: com uma só, o Hibernate nem usaria o SUBSELECT.
        var pagina = service.historico(aluno, 1, 2);

        assertThat(pagina.getTotalElements()).isEqualTo(FICHAS);
        assertThat(pagina.getContent()).hasSize(2)
                .allSatisfy(f -> assertThat(f.getDivisoes().getFirst().getExercicios()).hasSize(2));
        assertThat(estatisticas.getEntityLoadCount()).as("2 fichas + 2 divisões + 4 exercícios").isEqualTo(8);
    }
}
