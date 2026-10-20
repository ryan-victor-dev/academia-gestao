package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.RegrasDeAtraso;
import br.com.academiagestao.cobranca.dominio.TipoAcrescimo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** A linha única da configuração de atraso (V22). Sem JPA: o domínio é Java puro (ADR 0024). */
@Repository
class ConfiguracaoDaCobrancaRepository {

    private static final String COLUNAS = "tipo_acrescimo, acrescimo, juros_ao_mes, dias_para_bloquear, versao";

    private final JdbcTemplate jdbc;

    ConfiguracaoDaCobrancaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ConfiguracaoDaCobranca ler() {
        return jdbc.queryForObject("SELECT " + COLUNAS + " FROM configuracao_cobranca", this::mapear);
    }

    /** Trava a linha até o fim da transação: duas alterações simultâneas não se atropelam. */
    ConfiguracaoDaCobranca travar() {
        return jdbc.queryForObject("SELECT " + COLUNAS + " FROM configuracao_cobranca FOR UPDATE", this::mapear);
    }

    ConfiguracaoDaCobranca gravar(RegrasDeAtraso regras) {
        return jdbc.queryForObject("""
                UPDATE configuracao_cobranca
                SET tipo_acrescimo = ?, acrescimo = ?, juros_ao_mes = ?, dias_para_bloquear = ?, versao = versao + 1
                RETURNING""" + " " + COLUNAS, this::mapear,
                regras.tipoAcrescimo().name(), regras.acrescimo(), regras.jurosAoMes(), regras.diasParaBloquear());
    }

    private ConfiguracaoDaCobranca mapear(java.sql.ResultSet rs, int linha) throws java.sql.SQLException {
        return new ConfiguracaoDaCobranca(new RegrasDeAtraso(TipoAcrescimo.valueOf(rs.getString("tipo_acrescimo")),
                rs.getBigDecimal("acrescimo"), rs.getBigDecimal("juros_ao_mes"), rs.getInt("dias_para_bloquear")),
                rs.getLong("versao"));
    }

    record ConfiguracaoDaCobranca(RegrasDeAtraso regras, long versao) {
    }
}
