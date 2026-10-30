package br.com.academiagestao.avisos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Se a pessoa recebe os avisos da academia (eventos e comunicados, ADR 0047, decisão 8). O aviso de fatura não se
 * desliga. O aluno guarda pelo cadastro; a equipe, pelo login. Sem linha, recebe.
 */
@Service
class PreferenciasDeAvisos {

    /** De quem é a preferência: o aluno (pelo cadastro) ou alguém da equipe (pelo login). */
    record Dono(UUID alunoId, UUID usuarioId) {

        String coluna() {
            return alunoId != null ? "aluno_id" : "usuario_id";
        }

        UUID id() {
            return alunoId != null ? alunoId : usuarioId;
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock relogio;

    PreferenciasDeAvisos(JdbcTemplate jdbc, Clock relogio) {
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    boolean recebe(Dono dono) {
        List<Boolean> lida = jdbc.queryForList("SELECT recebe_da_academia FROM preferencias_de_avisos WHERE " + dono.coluna() + " = ?",
                Boolean.class, dono.id());
        return lida.isEmpty() || lida.getFirst();
    }

    @Transactional
    boolean mudar(Dono dono, boolean recebe) {
        jdbc.update("INSERT INTO preferencias_de_avisos (" + dono.coluna() + ", recebe_da_academia, atualizado_em) VALUES (?, ?, ?) "
                + "ON CONFLICT (" + dono.coluna() + ") WHERE " + dono.coluna() + " IS NOT NULL DO UPDATE SET "
                + "recebe_da_academia = EXCLUDED.recebe_da_academia, atualizado_em = EXCLUDED.atualizado_em",
                dono.id(), recebe, Timestamp.from(relogio.instant()));
        return recebe;
    }
}
