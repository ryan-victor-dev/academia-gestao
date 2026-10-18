package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.Auditoria;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Grava a trilha na tabela auditoria, só com os campos que mudaram. O usuário da aplicação
 * no banco só insere e lê nesta tabela (UsuarioDaAplicacaoNoBanco): nem uma injeção de SQL
 * apaga ou reescreve o histórico.
 */
@Component
class AuditoriaNoBanco implements Auditoria {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AuditoriaNoBanco(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // MANDATORY: fora de uma transação, a alteração poderia ser gravada sem o registro (ou
    // o contrário). Quem chama sem transação descobre na hora, e não numa auditoria furada.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Entidade entidade, UUID id, String acao, Map<String, ?> antes, Map<String, ?> depois) {
        jdbc.update("INSERT INTO auditoria (autor, entidade, entidade_id, acao, alteracoes) VALUES (?, ?, ?, ?, ?::jsonb)",
                autor(), entidade.name(), id, acao, json.writeValueAsString(alteracoes(antes, depois)));
    }

    static Map<String, Map<String, Object>> alteracoes(Map<String, ?> antes, Map<String, ?> depois) {
        Set<String> campos = new LinkedHashSet<>(antes.keySet());
        campos.addAll(depois.keySet());
        Map<String, Map<String, Object>> alteracoes = new LinkedHashMap<>();
        for (String campo : campos) {
            Object anterior = antes.get(campo);
            Object novo = depois.get(campo);
            if (!Objects.equals(anterior, novo)) {
                Map<String, Object> mudanca = new LinkedHashMap<>(); // aceita null (Map.of não)
                mudanca.put("antes", anterior);
                mudanca.put("depois", novo);
                alteracoes.put(campo, mudanca);
            }
        }
        return alteracoes;
    }

    /** O "sub" do token (o id do usuário); sem token, null = o próprio sistema. */
    private static String autor() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        return autenticacao instanceof JwtAuthenticationToken token ? token.getName() : null;
    }
}
