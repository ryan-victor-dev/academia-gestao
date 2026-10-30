package br.com.academiagestao.compartilhado;

import br.com.academiagestao.compartilhado.Identidade.IdentidadeDeGenero;
import br.com.academiagestao.compartilhado.Identidade.OrientacaoSexual;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Lê e grava a {@link Identidade} numa das duas tabelas da V56: a dos alunos (pelo cadastro) ou a da
 * equipe (pelo login). Cada módulo cria a sua, com o nome da tabela e da chave fixos no código.
 */
public final class IdentidadesNoBanco {

    private final JdbcTemplate jdbc;
    private final String buscar;
    private final String gravar;

    private IdentidadesNoBanco(JdbcTemplate jdbc, String tabela, String chave) {
        this.jdbc = jdbc;
        this.buscar = "SELECT identidade_de_genero, identidade_de_genero_outra, orientacao_sexual, orientacao_sexual_outra"
                + " FROM " + tabela + " WHERE " + chave + " = ?";
        this.gravar = "INSERT INTO " + tabela + " (" + chave + ", identidade_de_genero, identidade_de_genero_outra,"
                + " orientacao_sexual, orientacao_sexual_outra, atualizado_em) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (" + chave + ") DO UPDATE SET identidade_de_genero = EXCLUDED.identidade_de_genero,"
                + " identidade_de_genero_outra = EXCLUDED.identidade_de_genero_outra,"
                + " orientacao_sexual = EXCLUDED.orientacao_sexual,"
                + " orientacao_sexual_outra = EXCLUDED.orientacao_sexual_outra, atualizado_em = EXCLUDED.atualizado_em";
    }

    public static IdentidadesNoBanco dosAlunos(JdbcTemplate jdbc) {
        return new IdentidadesNoBanco(jdbc, "identidade_dos_alunos", "aluno_id");
    }

    public static IdentidadesNoBanco daEquipe(JdbcTemplate jdbc) {
        return new IdentidadesNoBanco(jdbc, "identidade_da_equipe", "usuario_id");
    }

    /** Sem linha, a pessoa não respondeu. */
    public Identidade buscar(UUID id) {
        return jdbc.query(buscar, rs -> rs.next()
                ? new Identidade(enumOuNulo(IdentidadeDeGenero.class, rs.getString(1)), rs.getString(2),
                        enumOuNulo(OrientacaoSexual.class, rs.getString(3)), rs.getString(4))
                : Identidade.NAO_RESPONDIDA, id);
    }

    /** Responder "nada" grava os nulos: a linha fica, sem nenhum dado. */
    public void gravar(UUID id, Identidade identidade, Instant agora) {
        jdbc.update(gravar, id, nomeOuNulo(identidade.identidadeDeGenero()), identidade.identidadeDeGeneroOutra(),
                nomeOuNulo(identidade.orientacaoSexual()), identidade.orientacaoSexualOutra(), Timestamp.from(agora));
    }

    private static <E extends Enum<E>> E enumOuNulo(Class<E> tipo, String nome) {
        return nome == null ? null : Enum.valueOf(tipo, nome);
    }

    private static String nomeOuNulo(Enum<?> valor) {
        return valor == null ? null : valor.name();
    }
}
