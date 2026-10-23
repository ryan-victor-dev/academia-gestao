package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.RegistroDeAcessos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    public void login(Tipo tipo, UUID usuarioId, String conta, String ip) {
        if (tipo == Tipo.CONSULTA_ALUNO) {
            throw new IllegalArgumentException("consulta de aluno vai por consultaDeAluno");
        }
        jdbc.update("INSERT INTO acessos (tipo, usuario_id, conta, ip) VALUES (?, ?, ?, ?)",
                tipo.name(), usuarioId, conta, ip);
    }
}
