package br.com.academiagestao.pagamentos;

import br.com.academiagestao.pagamentos.ConfiguracaoDoPagamentoOnline.Adiantar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;

/** A linha única da configuração do pagamento online (V42). */
@Repository
class ConfiguracaoDoPagamentoOnlineRepository {

    private static final String COLUNAS = "pix_ligado, boleto_ligado, adiantar, dias_do_boleto, versao";

    private final JdbcTemplate jdbc;

    ConfiguracaoDoPagamentoOnlineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ConfiguracaoDoPagamentoOnline ler() {
        return jdbc.queryForObject("SELECT " + COLUNAS + " FROM configuracao_pagamento_online", this::mapear);
    }

    /** Trava a linha até o fim da transação: duas alterações simultâneas não se atropelam. */
    ConfiguracaoDoPagamentoOnline travar() {
        return jdbc.queryForObject("SELECT " + COLUNAS + " FROM configuracao_pagamento_online FOR UPDATE", this::mapear);
    }

    ConfiguracaoDoPagamentoOnline gravar(ConfiguracaoDoPagamentoOnline c) {
        return jdbc.queryForObject("""
                UPDATE configuracao_pagamento_online
                SET pix_ligado = ?, boleto_ligado = ?, adiantar = ?, dias_do_boleto = ?, versao = versao + 1
                RETURNING""" + " " + COLUNAS, this::mapear,
                c.pixLigado(), c.boletoLigado(), c.adiantar().name(), c.diasDoBoleto());
    }

    private ConfiguracaoDoPagamentoOnline mapear(ResultSet rs, int linha) throws SQLException {
        return new ConfiguracaoDoPagamentoOnline(rs.getBoolean("pix_ligado"), rs.getBoolean("boleto_ligado"),
                Adiantar.valueOf(rs.getString("adiantar")), rs.getInt("dias_do_boleto"), rs.getLong("versao"));
    }
}
