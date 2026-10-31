package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.Identidade;
import br.com.academiagestao.compartilhado.Identidade.IdentidadeDeGenero;
import br.com.academiagestao.compartilhado.Identidade.OrientacaoSexual;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Quem é da equipe, com a data de nascimento (ADR 0048) e a identidade de gênero e orientação sexual (ADR 0047), para
 * o relatório de idades e gênero, que só o ADMIN abre. Dado sensível: só esse relatório usa esta porta.
 */
@Service
public class PerfisDaEquipe {

    /** {@code perfil}: ADMIN, RECEPCAO ou INSTRUTOR. Sem resposta, a identidade vem {@link Identidade#NAO_RESPONDIDA}. */
    public record PessoaDaEquipe(UUID id, String nome, String perfil, boolean ativo, LocalDate dataNascimento,
                                 Identidade identidade) {
    }

    private final JdbcTemplate jdbc;

    PerfisDaEquipe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Toda a equipe, pelo nome; com {@code comInativos} falso, só quem está ativo. */
    @Transactional(readOnly = true)
    public List<PessoaDaEquipe> todos(boolean comInativos) {
        return jdbc.query("""
                SELECT u.id, u.nome, u.perfil, u.ativo, u.data_nascimento, i.identidade_de_genero, i.identidade_de_genero_outra,
                       i.orientacao_sexual, i.orientacao_sexual_outra
                FROM usuarios u LEFT JOIN identidade_da_equipe i ON i.usuario_id = u.id
                WHERE u.perfil <> 'ALUNO' AND (? OR u.ativo)
                ORDER BY u.nome, u.id""", PerfisDaEquipe::pessoa, comInativos);
    }

    private static PessoaDaEquipe pessoa(ResultSet rs, int linha) throws SQLException {
        String genero = rs.getString("identidade_de_genero");
        String orientacao = rs.getString("orientacao_sexual");
        Identidade identidade = new Identidade(genero == null ? null : IdentidadeDeGenero.valueOf(genero),
                rs.getString("identidade_de_genero_outra"),
                orientacao == null ? null : OrientacaoSexual.valueOf(orientacao), rs.getString("orientacao_sexual_outra"));
        return new PessoaDaEquipe(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getString("perfil"),
                rs.getBoolean("ativo"), rs.getObject("data_nascimento", LocalDate.class), identidade);
    }
}
