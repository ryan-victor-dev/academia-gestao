package br.com.academiagestao.cobranca;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.Cronograma;
import br.com.academiagestao.cobranca.dominio.MatriculaParaCobranca;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.RegrasDeAtraso;
import br.com.academiagestao.cobranca.dominio.StatusCobranca;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gravação e leitura das cobranças com SQL (o domínio não tem JPA, ADR 0024). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CobrancaRepositoryTest {

    @Autowired
    CobrancaRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    private final UUID aluno = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID matricula = UUID.randomUUID();
    private final UUID usuario = UUID.randomUUID();
    private final LocalDate inicio = LocalDate.of(2026, 10, 17);

    @BeforeEach
    void preparar() {
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 2)", plano, "P " + plano);
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'R', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'P', 100, 2, ?, ?, now())""", matricula, aluno, plano, inicio, inicio.plusMonths(2).minusDays(1));
    }

    @AfterEach
    void limpar() {
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("planos", plano);
        limpeza.apagar("usuarios", usuario);
    }

    private List<Cobranca> gravarCronograma() {
        var dados = new MatriculaParaCobranca(matricula, aluno, new BigDecimal("100"), 2, false, inicio, List.of(), null);
        Cronograma.acertar(dados, List.of(), inicio, Instant.now()).novas().forEach(repository::inserir);
        return repository.travarDaMatricula(matricula);
    }

    @Test
    void gravaELeIgual() {
        Cobranca lida = gravarCronograma().getFirst();
        lida.pagar(RegrasDeAtraso.PADRAO, inicio, new BigDecimal("100"), MeioDePagamento.PIX, usuario,
                Instant.parse("2026-10-17T15:00:00.123456Z"));
        repository.atualizar(lida);

        Cobranca relida = repository.travarDaMatricula(matricula).getFirst();
        assertThat(relida.getStatus()).isEqualTo(StatusCobranca.PAGA);
        assertThat(relida.getPagamento()).isEqualTo(lida.getPagamento());
        assertThat(relida.getVencimento()).isEqualTo(inicio);
        assertThat(relida.getValor()).isEqualByComparingTo("100");
        assertThat(relida.getVersao()).isEqualTo(1);

        relida.estornar();
        relida.cancelar("Engano", usuario, Instant.parse("2026-10-18T10:00:00Z"));
        repository.atualizar(relida);
        Cobranca cancelada = repository.travarDaMatricula(matricula).getFirst();
        assertThat(cancelada.getPagamento()).isNull();
        assertThat(cancelada.getCancelamento()).isEqualTo(relida.getCancelamento());
    }

    /** Duas gravações a partir da mesma leitura: a segunda não sobrescreve a primeira. */
    @Test
    void versaoVelhaNaoGrava() {
        Cobranca primeira = gravarCronograma().getFirst();
        Cobranca mesmaLeitura = repository.travarDaMatricula(matricula).getFirst();
        primeira.cancelar("Engano", usuario, Instant.now());
        repository.atualizar(primeira);

        mesmaLeitura.pagar(RegrasDeAtraso.PADRAO, inicio, new BigDecimal("100"), MeioDePagamento.PIX, usuario,
                Instant.now());
        assertThatThrownBy(() -> repository.atualizar(mesmaLeitura))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(repository.travarDaMatricula(matricula).getFirst().getStatus()).isEqualTo(StatusCobranca.CANCELADA);
    }

    @Test
    void semCronogramaSoAsQueNaoTem() {
        UUID inexistente = UUID.randomUUID();
        assertThat(repository.semCronograma(List.of(matricula, inexistente))).containsExactlyInAnyOrder(matricula, inexistente);
        repository.registrarCronograma(matricula, inicio);
        assertThat(repository.semCronograma(List.of(matricula, inexistente))).containsExactly(inexistente);
        assertThat(repository.aPartirDe(matricula)).contains(inicio);
        assertThat(repository.semCronograma(List.of())).isEmpty();
    }
}
