package br.com.academiagestao;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apaga os dados de todas as tabelas, filhas antes das mães (R5). A ordem sai das chaves
 * estrangeiras do próprio banco, a cada chamada: tabela nova entra sozinha, sem mexer nos
 * testes (antes, cada tabela nova pedia ajustes em várias classes). Ver {@link #FICAM}.
 *
 * DELETE, e não TRUNCATE: o TRUNCATE trava a tabela inteira e esperaria qualquer transação
 * aberta (a de uma thread de teste de concorrência, por exemplo) até o tempo máximo da consulta.
 */
public class LimpezaDoBanco {

    /**
     * Não são dados de teste: o histórico do Flyway e as linhas que as migrações criam e a
     * aplicação só altera (a configuração da cobrança, V22). Quem altera restaura.
     */
    static final List<String> FICAM = List.of("flyway_schema_history", "configuracao_cobranca");

    private final JdbcTemplate jdbc;

    public LimpezaDoBanco(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void apagarTudo() {
        for (String tabela : ordemParaApagar()) {
            jdbc.update("DELETE FROM " + tabela);
        }
    }

    /** Toda tabela vem depois das que apontam para ela. */
    List<String> ordemParaApagar() {
        Set<String> restantes = new HashSet<>(jdbc.queryForList("""
                SELECT quote_ident(tablename) FROM pg_tables
                WHERE schemaname = 'public' AND NOT (tablename = ANY (?))""", String.class,
                (Object) FICAM.toArray(String[]::new)));
        // mãe -> filhas que ainda apontam para ela (autorreferência não atrapalha um DELETE só)
        Map<String, Set<String>> filhas = new HashMap<>();
        jdbc.query("""
                SELECT quote_ident(filha.relname), quote_ident(mae.relname)
                FROM pg_constraint c
                JOIN pg_class filha ON filha.oid = c.conrelid
                JOIN pg_class mae ON mae.oid = c.confrelid
                JOIN pg_namespace n ON n.oid = filha.relnamespace
                WHERE c.contype = 'f' AND n.nspname = 'public' AND filha.oid <> mae.oid""",
                linha -> {
                    filhas.computeIfAbsent(linha.getString(2), m -> new HashSet<>()).add(linha.getString(1));
                });
        List<String> ordem = new ArrayList<>();
        while (!restantes.isEmpty()) {
            List<String> semFilhas = restantes.stream()
                    .filter(t -> filhas.getOrDefault(t, Set.of()).stream().noneMatch(restantes::contains))
                    .sorted()
                    .toList();
            if (semFilhas.isEmpty()) {
                throw new IllegalStateException("Chaves estrangeiras em ciclo entre " + restantes
                        + ": apague essas tabelas na mão");
            }
            ordem.addAll(semFilhas);
            semFilhas.forEach(restantes::remove);
        }
        return ordem;
    }
}
