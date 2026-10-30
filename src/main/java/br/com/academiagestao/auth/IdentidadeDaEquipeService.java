package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Identidade;
import br.com.academiagestao.compartilhado.IdentidadesNoBanco;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.USUARIO;

/**
 * Identidade de gênero e orientação sexual de quem é da equipe (ADR 0047, decisão 2). A do aluno fica no
 * cadastro dele, e não no login: o login de aluno é recusado aqui. A trilha registra que mudou, sem os
 * valores (o dado sensível não fica 5 anos na trilha depois de apagado).
 */
@Service
class IdentidadeDaEquipeService {

    private final JdbcTemplate jdbc;
    private final IdentidadesNoBanco identidades;
    private final Auditoria auditoria;
    private final Clock relogio;

    IdentidadeDaEquipeService(JdbcTemplate jdbc, Auditoria auditoria, Clock relogio) {
        this.jdbc = jdbc;
        this.identidades = IdentidadesNoBanco.daEquipe(jdbc);
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    Identidade buscar(UUID usuarioId) {
        garantirQueEhDaEquipe(usuarioId, "");
        return identidades.buscar(usuarioId);
    }

    /** O login fica travado (FOR SHARE) até o fim: uma exclusão ao mesmo tempo espera. */
    @Transactional
    Identidade gravar(UUID usuarioId, Identidade identidade) {
        garantirQueEhDaEquipe(usuarioId, " FOR SHARE");
        identidades.gravar(usuarioId, identidade, relogio.instant());
        auditoria.registrar(USUARIO, usuarioId, "IDENTIDADE_ALTERADA", Map.of(), Map.of());
        return identidade;
    }

    private void garantirQueEhDaEquipe(UUID usuarioId, String trava) {
        List<String> perfil = jdbc.queryForList("SELECT perfil FROM usuarios WHERE id = ?" + trava, String.class, usuarioId);
        if (perfil.isEmpty() || Perfil.ALUNO.name().equals(perfil.getFirst())) {
            throw new UsuarioNaoEncontradoException("Usuário da equipe não encontrado");
        }
    }
}
