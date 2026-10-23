package br.com.academiagestao.cobranca;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Caixa aberto direto no banco, para os testes que pagam ou gravam lançamentos (ADR 0031). O
 * caixa é um por dia e os testes dividem o banco: quem abre apaga no fim, com
 * {@code limpeza.apagar("caixas", id)}, que leva os lançamentos e os fechamentos dele.
 */
final class CaixaDeTeste {

    private CaixaDeTeste() {
    }

    static UUID abrir(JdbcTemplate jdbc, LocalDate dia, UUID usuario) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO caixas (id, dia, troco, situacao, aberto_em, aberto_por) "
                + "VALUES (?, ?, 0, 'ABERTO', now(), ?)", id, dia, usuario);
        return id;
    }
}
