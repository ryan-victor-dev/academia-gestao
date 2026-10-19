package br.com.academiagestao.auditoria;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Diagnóstico de 2026-10-18 (E1): o histórico de uma entidade só pelo id varria a trilha
 * inteira. A rota de verdade, com dados suficientes para o planejador preferir o índice.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IndicesDaAuditoriaTest {

    // Marca as linhas deste teste: os outros dividem o mesmo banco.
    private static final String ACAO = "TESTE_INDICE";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM auditoria WHERE acao = ?", ACAO);
    }

    private long usosDo(String indice) {
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return jdbc.queryForObject("SELECT idx_scan FROM pg_stat_user_indexes WHERE indexrelname = ?",
                Long.class, indice);
    }

    @Test
    void historicoPeloIdUsaOIndiceDoId() throws Exception {
        jdbc.update("""
                INSERT INTO auditoria (entidade, entidade_id, acao, alteracoes)
                SELECT 'ALUNO', gen_random_uuid(), ?, '{}' FROM generate_series(1, 20000)""", ACAO);
        UUID procurado = UUID.randomUUID();
        jdbc.update("INSERT INTO auditoria (entidade, entidade_id, acao, alteracoes) VALUES ('ALUNO', ?, ?, '{}')",
                procurado, ACAO);
        jdbc.execute("ANALYZE auditoria");
        long antes = usosDo("ix_auditoria_entidade_id");

        mvc.perform(get("/api/auditoria").param("entidadeId", procurado.toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].entidadeId").value(procurado.toString()));

        // A conexão da aplicação grava as estatísticas com atraso (até alguns segundos).
        long usos = 0;
        for (int i = 0; i < 40 && usos == 0; i++) {
            Thread.sleep(500);
            usos = usosDo("ix_auditoria_entidade_id") - antes;
        }
        assertThat(usos).as("leituras do índice ix_auditoria_entidade_id").isPositive();
    }
}
