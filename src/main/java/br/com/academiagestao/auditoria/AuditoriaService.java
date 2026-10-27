package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.Auditoria.Entidade;
import br.com.academiagestao.compartilhado.Pagina;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Consulta da trilha, do mais novo para o mais antigo. Quem pode ver é o controller que diz. */
@Service
class AuditoriaService {

    private static final TypeReference<Map<String, Object>> OBJETO = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    // Números como BigDecimal: dinheiro não passa por double (129.90 continua 129.90).
    private final ObjectReader leitor;

    AuditoriaService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.leitor = json.readerFor(OBJETO).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Transactional(readOnly = true)
    Pagina<Registro> listar(Entidade entidade, UUID entidadeId, int pagina, int tamanho) {
        StringBuilder filtro = new StringBuilder(" WHERE true");
        List<Object> parametros = new ArrayList<>();
        if (entidade != null) {
            filtro.append(" AND a.entidade = ?");
            parametros.add(entidade.name());
        }
        if (entidadeId != null) {
            filtro.append(" AND a.entidade_id = ?");
            parametros.add(entidadeId);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM auditoria a" + filtro, Long.class, parametros.toArray());

        List<Object> daPagina = new ArrayList<>(parametros);
        daPagina.add(tamanho);
        daPagina.add((long) pagina * tamanho);
        // O nome do autor vem do cadastro de usuários (pode ter mudado depois); o id é o que vale.
        List<Registro> registros = jdbc.query("""
                        SELECT a.id, a.momento, a.autor, u.nome AS autor_nome, a.entidade, a.entidade_id, a.acao,
                               a.alteracoes::text AS alteracoes
                        FROM auditoria a LEFT JOIN usuarios u ON u.id::text = a.autor""" + filtro
                        + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new Registro(rs.getLong("id"), rs.getTimestamp("momento").toInstant(),
                        rs.getString("autor"), rs.getString("autor_nome"), rs.getString("entidade"),
                        rs.getObject("entidade_id", UUID.class), rs.getString("acao"),
                        leitor.readValue(rs.getString("alteracoes"))),
                daPagina.toArray());
        return new Pagina<>(registros, pagina, tamanho, total, (int) ((total + tamanho - 1) / tamanho));
    }

    /** autor nulo = o próprio sistema (inicialização, recuperação de ADMIN pela linha de comando). */
    record Registro(long id, Instant momento, String autor, String autorNome, String entidade,
                    UUID entidadeId, String acao, Map<String, Object> alteracoes) {
    }
}
