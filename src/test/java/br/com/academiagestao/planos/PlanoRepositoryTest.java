package br.com.academiagestao.planos;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PlanoRepositoryTest {

    @Autowired
    PlanoRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpar() {
        jdbc.update("DELETE FROM planos");
    }

    private static Plano plano(String nome) {
        return Plano.novo(new DadosPlano(nome, new BigDecimal("129.90"), 1));
    }

    @Test
    void salvaELeDeVolta() {
        Plano salvo = repository.saveAndFlush(plano("Mensal"));

        Plano lido = repository.findById(salvo.getId()).orElseThrow();
        assertThat(lido.getValorMensal()).isEqualByComparingTo("129.90");
        assertThat(lido.getVersao()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mensal", "MENSAL", "mensal", "Mênsal", "MÉNSAL"})
    void bancoRecusaDoisAtivosComOMesmoNome(String outroNome) {
        repository.saveAndFlush(plano("Mensal"));

        assertThatThrownBy(() -> repository.saveAndFlush(plano(outroNome)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inativoPodeTerOMesmoNomeDeUmAtivo() {
        Plano antigo = plano("Mensal");
        antigo.inativar();
        repository.saveAndFlush(antigo);

        assertThatCode(() -> repository.saveAndFlush(plano("Mensal"))).doesNotThrowAnyException();
    }

    @Test
    void consultaDeNomeRepetidoUsaAMesmaRegraDoBanco() {
        Plano mensal = repository.saveAndFlush(plano("Mensal"));
        Plano inativo = plano("Anual");
        inativo.inativar();
        repository.saveAndFlush(inativo);

        assertThat(repository.existeOutroAtivoComNome("MÊNSAL", UUID.randomUUID())).isTrue();
        // O próprio plano não conta (edição sem trocar o nome).
        assertThat(repository.existeOutroAtivoComNome("Mensal", mensal.getId())).isFalse();
        // Inativo não conta.
        assertThat(repository.existeOutroAtivoComNome("anual", UUID.randomUUID())).isFalse();
    }

    @Test
    void bancoRecusaDuracaoAcimaDe60MesesMesmoSemPassarPeloJava() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, 'X', 10, 61)",
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
