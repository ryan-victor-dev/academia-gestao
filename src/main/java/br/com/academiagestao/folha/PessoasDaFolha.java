package br.com.academiagestao.folha;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * As pessoas da folha no banco (V64), junto com a equipe que tem login: quem tem login aparece mesmo sem linha em
 * {@code pessoas_da_folha}, para o ADMIN definir o vínculo. Login de aluno nunca entra.
 */
@Repository
class PessoasDaFolha {

    private static final String SELECT = """
            SELECT p.id, u.id AS usuario_id, coalesce(u.nome, p.nome) AS nome, coalesce(u.cpf, p.cpf) AS cpf, u.perfil,
                   p.vinculo, p.valor_combinado, coalesce(u.ativo, p.ativo) AS ativo, coalesce(p.versao, 0) AS versao
            FROM pessoas_da_folha p
            FULL JOIN (SELECT id, nome, cpf, perfil, ativo FROM usuarios WHERE perfil <> 'ALUNO') u ON u.id = p.usuario_id""";

    private final JdbcTemplate jdbc;

    PessoasDaFolha(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Pelo nome; sem {@code incluirInativos}, só quem está ativo (o login ou a pessoa sem login). */
    List<PessoaDaFolha> listar(boolean incluirInativos) {
        String filtro = incluirInativos ? "" : " WHERE coalesce(u.ativo, p.ativo)";
        return jdbc.query(SELECT + filtro + " ORDER BY lower(coalesce(u.nome, p.nome)), coalesce(p.id, u.id)",
                PessoasDaFolha::pessoa);
    }

    Optional<PessoaDaFolha> buscar(UUID id) {
        return jdbc.query(SELECT + " WHERE p.id = ?", PessoasDaFolha::pessoa, id).stream().findFirst();
    }

    /** Trava a linha até o fim da transação: duas edições ao mesmo tempo esperam uma pela outra. */
    Optional<PessoaDaFolha> travar(UUID id) {
        if (jdbc.queryForList("SELECT 1 FROM pessoas_da_folha WHERE id = ? FOR UPDATE", Integer.class, id).isEmpty()) {
            return Optional.empty();
        }
        return buscar(id);
    }

    /**
     * O perfil do login da equipe, travado para leitura até o fim da transação (a exclusão do login, ADR 0043, espera,
     * e não dá 500 na chave estrangeira no meio). Vazio: o login não existe ou é de aluno.
     */
    Optional<String> travarLoginDaEquipe(UUID usuarioId) {
        return jdbc.queryForList("SELECT perfil FROM usuarios WHERE id = ? AND perfil <> 'ALUNO' FOR SHARE",
                String.class, usuarioId).stream().findFirst();
    }

    /** Se o CPF é de alguém da equipe com login (quem tem login entra na folha pelo login). */
    boolean cpfDeLoginDaEquipe(String cpf) {
        return !jdbc.queryForList("SELECT 1 FROM usuarios WHERE cpf = ?", Integer.class, cpf).isEmpty();
    }

    void inserirComLogin(UUID id, UUID usuarioId, Combinado combinado, Instant agora) {
        jdbc.update("""
                INSERT INTO pessoas_da_folha (id, usuario_id, vinculo, valor_combinado, criado_em)
                VALUES (?, ?, ?, ?, ?)""", id, usuarioId, combinado.vinculo().name(), combinado.valor(), Timestamp.from(agora));
    }

    void inserirSemLogin(UUID id, SemLogin dados, Combinado combinado, Instant agora) {
        jdbc.update("""
                INSERT INTO pessoas_da_folha (id, nome, cpf, vinculo, valor_combinado, criado_em)
                VALUES (?, ?, ?, ?, ?, ?)""", id, dados.nome(), dados.cpf(), combinado.vinculo().name(), combinado.valor(),
                Timestamp.from(agora));
    }

    /** Quem tem login: só o combinado. */
    void atualizarCombinado(UUID id, Combinado combinado) {
        jdbc.update("UPDATE pessoas_da_folha SET vinculo = ?, valor_combinado = ?, versao = versao + 1 WHERE id = ?",
                combinado.vinculo().name(), combinado.valor(), id);
    }

    /** Quem não tem login: nome, CPF, combinado e situação. */
    void atualizarSemLogin(UUID id, SemLogin dados, Combinado combinado, boolean ativo) {
        jdbc.update("""
                UPDATE pessoas_da_folha SET nome = ?, cpf = ?, vinculo = ?, valor_combinado = ?, ativo = ?, versao = versao + 1
                WHERE id = ?""", dados.nome(), dados.cpf(), combinado.vinculo().name(), combinado.valor(), ativo, id);
    }

    private static PessoaDaFolha pessoa(ResultSet rs, int linha) throws SQLException {
        String vinculo = rs.getString("vinculo");
        BigDecimal valor = rs.getBigDecimal("valor_combinado");
        return new PessoaDaFolha(rs.getObject("id", UUID.class), rs.getObject("usuario_id", UUID.class), rs.getString("nome"),
                rs.getString("cpf"), rs.getString("perfil"), vinculo == null ? null : new Combinado(Vinculo.valueOf(vinculo), valor),
                rs.getBoolean("ativo"), rs.getLong("versao"));
    }
}
