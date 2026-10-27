package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.Pagina;
import br.com.academiagestao.compartilhado.RegistroDeAcessos.Tipo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Consulta do registro de acessos (V38, ADR 0031), do mais novo para o mais antigo. */
@Service
class AcessosService {

    private final JdbcTemplate jdbc;

    AcessosService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Pagina<Acesso> listar(Tipo tipo, UUID usuarioId, UUID alunoId, String conta, int pagina, int tamanho) {
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
        List<Acesso> acessos = jdbc.query("""
                        SELECT a.id, a.momento, a.tipo, a.usuario_id, u.nome AS usuario_nome, a.conta, a.ip, a.aluno_id
                        FROM acessos a LEFT JOIN usuarios u ON u.id = a.usuario_id""" + filtro
                        + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new Acesso(rs.getLong("id"), rs.getTimestamp("momento").toInstant(),
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
    record Acesso(long id, Instant momento, Tipo tipo, UUID usuarioId, String usuarioNome, String conta,
                  String ip, UUID alunoId) {
    }
}
