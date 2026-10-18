package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.Auditoria.Entidade;
import br.com.academiagestao.compartilhado.Pagina;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta da trilha, só para o ADMIN: tem dados pessoais (valores anteriores de cadastros)
 * e mostra o que cada funcionário fez. Do mais novo para o mais antigo.
 * Ex.: GET /api/auditoria?entidade=PLANO&entidadeId=...&pagina=0&tamanho=20
 */
@RestController
@RequestMapping("/api/auditoria")
class AuditoriaController {

    private static final TypeReference<Map<String, Object>> OBJETO = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    // Números como BigDecimal: dinheiro não passa por double (129.90 continua 129.90).
    private final ObjectReader leitor;

    AuditoriaController(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.leitor = json.readerFor(OBJETO).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    Pagina<RegistroResponse> listar(
            @RequestParam(required = false) Entidade entidade,
            @RequestParam(required = false) UUID entidadeId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
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
        List<RegistroResponse> registros = jdbc.query("""
                        SELECT a.id, a.momento, a.autor, u.nome AS autor_nome, a.entidade, a.entidade_id, a.acao,
                               a.alteracoes::text AS alteracoes
                        FROM auditoria a LEFT JOIN usuarios u ON u.id::text = a.autor""" + filtro
                        + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new RegistroResponse(rs.getLong("id"), rs.getTimestamp("momento").toInstant(),
                        rs.getString("autor"), rs.getString("autor_nome"), rs.getString("entidade"),
                        rs.getObject("entidade_id", UUID.class), rs.getString("acao"),
                        leitor.readValue(rs.getString("alteracoes"))),
                daPagina.toArray());
        return new Pagina<>(registros, pagina, tamanho, total, (int) ((total + tamanho - 1) / tamanho));
    }

    /** autor nulo = o próprio sistema (inicialização, recuperação de ADMIN pela linha de comando). */
    record RegistroResponse(long id, Instant momento, String autor, String autorNome, String entidade,
                            UUID entidadeId, String acao, Map<String, Object> alteracoes) {
    }
}
