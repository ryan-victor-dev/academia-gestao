package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Identidade;
import br.com.academiagestao.compartilhado.IdentidadesNoBanco;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.ALUNO;

/**
 * Identidade de gênero e orientação sexual do aluno (ADR 0047, decisão 2). Quem chama já conferiu que é o
 * ADMIN ou o próprio aluno. A trilha registra que mudou, mas não os valores: a trilha fica 5 anos (ADR 0022),
 * e o dado sensível ficaria lá depois de a pessoa apagá-lo.
 */
@Service
class IdentidadeDoAlunoService {

    private final JdbcTemplate jdbc;
    private final IdentidadesNoBanco identidades;
    private final Auditoria auditoria;
    private final Clock relogio;

    IdentidadeDoAlunoService(JdbcTemplate jdbc, Auditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.identidades = IdentidadesNoBanco.dosAlunos(jdbc);
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    Identidade buscar(UUID alunoId) {
        garantirQueExiste(alunoId, "");
        return identidades.buscar(alunoId);
    }

    /**
     * O cadastro fica travado (FOR SHARE) até o fim: uma exclusão ao mesmo tempo espera, em vez de a chave
     * estrangeira dar 500 no meio da gravação.
     */
    @Transactional
    Identidade gravar(UUID alunoId, Identidade identidade) {
        garantirQueExiste(alunoId, " FOR SHARE");
        identidades.gravar(alunoId, identidade, relogio.instant());
        auditoria.registrar(ALUNO, alunoId, "IDENTIDADE_ALTERADA", Map.of(), Map.of());
        return identidade;
    }

    private void garantirQueExiste(UUID alunoId, String trava) {
        if (jdbc.queryForList("SELECT 1 FROM alunos WHERE id = ?" + trava, Integer.class, alunoId).isEmpty()) {
            throw new AlunoNaoEncontradoException(alunoId);
        }
    }
}
