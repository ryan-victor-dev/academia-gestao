package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.RegistroDeAcessos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/** O registro de acessos na tabela {@code acessos} (V38): só INSERT, como a trilha. */
@Component
class AcessosNoBanco implements RegistroDeAcessos {

    private final JdbcTemplate jdbc;

    AcessosNoBanco(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // NEVER: dentro de uma transação, o registro seria desfeito junto com ela (a do login com
    // senha errada é), ou recusado (a de leitura). Fora dela, cada INSERT se confirma sozinho,
    // sem pedir uma segunda conexão (ADR 0020).
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void consultaDeAluno(UUID usuarioId, UUID alunoId, String ip) {
        jdbc.update("INSERT INTO acessos (tipo, usuario_id, aluno_id, ip) VALUES ('CONSULTA_ALUNO', ?, ?, ?)",
                usuarioId, alunoId, ip);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void relatorioDePerfil(UUID usuarioId, String ip) {
        jdbc.update("INSERT INTO acessos (tipo, usuario_id, ip) VALUES ('RELATORIO_PERFIL', ?, ?)", usuarioId, ip);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void login(TipoDeLogin tipo, UUID usuarioId, String conta, String ip) {
        jdbc.update("INSERT INTO acessos (tipo, usuario_id, conta, ip) VALUES (?, ?, ?, ?)",
                tipo.tipo().name(), usuarioId, conta, ip);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void loginBloqueado(UUID usuarioId, String conta, String ip, Duration janela) {
        // Um comando só: a procura e a gravação não abrem uma transação nem pedem outra conexão.
        jdbc.update("""
                INSERT INTO acessos (tipo, usuario_id, conta, ip)
                SELECT 'LOGIN_BLOQUEADO', ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM acessos WHERE tipo = 'LOGIN_BLOQUEADO' AND conta = ? AND ip = ?
                                  AND momento > now() - make_interval(secs => ?))""",
                usuarioId, conta, ip, conta, ip, (double) janela.toSeconds());
    }
}
