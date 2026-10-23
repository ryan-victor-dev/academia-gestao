package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.Pagina;
import br.com.academiagestao.compartilhado.RegistroDeAcessos.Tipo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Consulta do registro de acessos (V38, ADR 0031), só para o ADMIN (decisão do dono): quem
 * abriu o cadastro de qual aluno, e os logins com a conta e o IP. Do mais novo para o mais
 * antigo. Ex.: GET /api/auditoria/acessos?alunoId=...  ou  ?conta=fulano@email.com&tipo=LOGIN_FALHO
 */
@RestController
@RequestMapping("/api/auditoria/acessos")
class AcessosController {

    private final JdbcTemplate jdbc;

    AcessosController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    Pagina<AcessoResponse> listar(
            @RequestParam(required = false) Tipo tipo,
            @RequestParam(required = false) UUID usuarioId,
            @RequestParam(required = false) UUID alunoId,
            @RequestParam(required = false) String conta,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
        StringBuilder filtro = new StringBuilder(" WHERE true");
        List<Object> parametros = new ArrayList<>();
        if (tipo != null) {
            filtro.append(" AND a.tipo = ?");
            parametros.add(tipo.name());
        }
        if (usuarioId != null) {
            filtro.append(" AND a.usuario_id = ?");
            parametros.add(usuarioId);
        }
        if (alunoId != null) {
            filtro.append(" AND a.aluno_id = ?");
            parametros.add(alunoId);
        }
        if (conta != null && !conta.isBlank()) {
            // Como o login grava: sem espaços nas pontas e em minúsculas.
            filtro.append(" AND a.conta = ?");
            parametros.add(conta.trim().toLowerCase(Locale.ROOT));
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM acessos a" + filtro, Long.class, parametros.toArray());

        List<Object> daPagina = new ArrayList<>(parametros);
        daPagina.add(tamanho);
        daPagina.add((long) pagina * tamanho);
        // O nome vem do cadastro de usuários (pode ter mudado depois); o id é o que vale.
        List<AcessoResponse> acessos = jdbc.query("""
                        SELECT a.id, a.momento, a.tipo, a.usuario_id, u.nome AS usuario_nome, a.conta, a.ip, a.aluno_id
                        FROM acessos a LEFT JOIN usuarios u ON u.id = a.usuario_id""" + filtro
                        + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new AcessoResponse(rs.getLong("id"), rs.getTimestamp("momento").toInstant(),
                        Tipo.valueOf(rs.getString("tipo")), rs.getObject("usuario_id", UUID.class),
                        rs.getString("usuario_nome"), rs.getString("conta"), rs.getString("ip"),
                        rs.getObject("aluno_id", UUID.class)),
                daPagina.toArray());
        return new Pagina<>(acessos, pagina, tamanho, total, (int) ((total + tamanho - 1) / tamanho));
    }

    /**
     * {@code usuarioId} nulo: login com uma conta que não existe, ou bloqueado pelo limite (a
     * conta não é consultada antes dele). {@code conta}: o e-mail digitado, só nos logins.
     */
    record AcessoResponse(long id, Instant momento, Tipo tipo, UUID usuarioId, String usuarioNome, String conta,
                          String ip, UUID alunoId) {
    }
}
