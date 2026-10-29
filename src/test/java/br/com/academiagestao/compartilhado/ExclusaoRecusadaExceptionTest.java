package br.com.academiagestao.compartilhado;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** Só a chave estrangeira é "tem histórico" (ADR 0043); outra recusa do banco continua 500. */
class ExclusaoRecusadaExceptionTest {

    private static DataIntegrityViolationException recusa(String codigo) {
        // Como o Hibernate entrega: a SQLException fica algumas camadas abaixo.
        return new DataIntegrityViolationException("recusa",
                new RuntimeException("camada", new SQLException("do banco", codigo)));
    }

    @Test
    void chaveEstrangeiraEhHistorico() {
        assertThat(ExclusaoRecusadaException.temHistorico(recusa("23503"))).isTrue();
    }

    @Test
    void outraRecusaNaoEh() {
        assertThat(ExclusaoRecusadaException.temHistorico(recusa("23505"))).as("índice único").isFalse();
        assertThat(ExclusaoRecusadaException.temHistorico(recusa("23514"))).as("CHECK").isFalse();
        assertThat(ExclusaoRecusadaException.temHistorico(new DataIntegrityViolationException("sem causa"))).isFalse();
    }

    @Test
    void levaOTituloEODetalhe() {
        ExclusaoRecusadaException e = new ExclusaoRecusadaException("Plano com matrículas", "inative-o");
        assertThat(e.titulo()).isEqualTo("Plano com matrículas");
        assertThat(e.getMessage()).isEqualTo("inative-o");
    }
}
