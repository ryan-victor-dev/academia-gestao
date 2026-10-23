package br.com.academiagestao.auditoria;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Apaga, todo dia, os registros da trilha com mais de 5 anos (V21) e os do registro de
 * acessos com mais de 1 ano (V38), decisões do dono. O prazo e o apagamento ficam nas funções
 * do banco: a aplicação não tem DELETE em nenhum dos dois.
 */
@Component
class RetencaoDaAuditoria {

    private static final Logger log = LoggerFactory.getLogger(RetencaoDaAuditoria.class);
    private static final int LOTE = 10_000;

    private final JdbcTemplate jdbc;
    private final int lote;

    @Autowired
    RetencaoDaAuditoria(JdbcTemplate jdbc) {
        this(jdbc, LOTE);
    }

    RetencaoDaAuditoria(JdbcTemplate jdbc, int lote) {
        this.jdbc = jdbc;
        this.lote = lote;
    }

    // Às 4h30, depois do backup (a partir das 3h): o dia ainda tem o backup com o que sai hoje.
    @Scheduled(cron = "${academia.auditoria.horario-da-retencao:0 30 4 * * *}", zone = "America/Sao_Paulo")
    void apagarVencidos() {
        int trilha = apagarEmLotes("apagar_auditoria_vencida");
        if (trilha > 0) {
            log.info("Retenção da auditoria: {} registro(s) com mais de 5 anos apagado(s)", trilha);
        }
        int acessos = apagarEmLotes("apagar_acessos_vencidos");
        if (acessos > 0) {
            log.info("Retenção dos acessos: {} registro(s) com mais de 1 ano apagado(s)", acessos);
        }
    }

    private int apagarEmLotes(String funcao) {
        int total = 0;
        int apagados;
        do {
            // Um lote por chamada, cada um na sua transação curta.
            apagados = jdbc.queryForObject("SELECT " + funcao + "(?)", Integer.class, lote);
            total += apagados;
        } while (apagados == lote);
        return total;
    }
}
