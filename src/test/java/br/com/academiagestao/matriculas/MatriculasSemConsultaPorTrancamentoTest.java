package br.com.academiagestao.matriculas;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6 (revisão de 2026-10-17): os trancamentos vêm junto com cada matrícula, e uma lista de N
 * matrículas fazia N consultas a mais, uma por matrícula. Com o BatchSize, os trancamentos
 * da lista inteira vêm numa consulta só. Só a contagem do Hibernate mostra a diferença.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MatriculasSemConsultaPorTrancamentoTest {

    private static final int MATRICULAS = 5;

    @Autowired
    MatriculaRepository repository;

    @Autowired
    MatriculaService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    EntityManagerFactory emf;

    private final UUID aluno = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID usuario = UUID.randomUUID();

    @AfterEach
    void limpar() {
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("usuarios", usuario);
        limpeza.apagar("planos", plano);
    }

    @Test
    void historicoDoAlunoLeOsTrancamentosNumaConsultaSo() {
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 129.90, 1)",
                plano, "Plano " + plano);
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'R', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
        // Uma matrícula de um mês a cada três meses, cada uma com um trancamento.
        for (int i = 0; i < MATRICULAS; i++) {
            LocalDate inicio = LocalDate.parse("2027-01-10").plusMonths(3L * i);
            Matricula m = Matricula.nova(aluno, new PlanoContratado(plano, "Mensal", new BigDecimal("129.90"), 1),
                    inicio, em(inicio));
            repository.saveAndFlush(m);
            m.trancar(inicio.plusDays(5), inicio.plusDays(15), "Viagem", usuario, em(inicio.plusDays(2)));
            repository.saveAndFlush(m);
        }
        Statistics estatisticas = emf.unwrap(SessionFactory.class).getStatistics();
        estatisticas.clear();

        List<Matricula> historico = service.listarDoAluno(aluno);

        assertThat(historico).hasSize(MATRICULAS).allSatisfy(m -> assertThat(m.getTrancamentos()).hasSize(1));
        assertThat(estatisticas.getPrepareStatementCount()).as("as matrículas + os trancamentos de todas")
                .isEqualTo(2);
    }

    private static java.time.Instant em(LocalDate dia) {
        return LocalDateTime.of(dia, java.time.LocalTime.of(10, 0)).atZone(Matricula.FUSO).toInstant();
    }
}
