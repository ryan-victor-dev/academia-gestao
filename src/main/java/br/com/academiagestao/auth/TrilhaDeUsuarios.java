package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.Auditoria;
import org.springframework.stereotype.Component;

import java.util.Map;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.USUARIO;

/** A trilha de auditoria dos logins (equipe e alunos). Nunca grava senha nem hash. */
@Component
class TrilhaDeUsuarios {

    private final Auditoria auditoria;

    TrilhaDeUsuarios(Auditoria auditoria) {
        this.auditoria = auditoria;
    }

    static Map<String, Object> retrato(Usuario usuario) {
        return Auditoria.campos("nome", usuario.getNome(), "email", usuario.getEmail(), "cpf", usuario.getCpf(), "perfil", usuario.getPerfil(),
                "alunoId", usuario.getAlunoId(), "ativo", usuario.isAtivo(),
                "somenteLeitura", usuario.isSomenteLeitura());
    }

    void criado(Usuario usuario) {
        auditoria.registrar(USUARIO, usuario.getId(), "CRIADO", Map.of(), retrato(usuario));
    }

    void alterado(Usuario usuario, String acao, Map<String, Object> antes) {
        auditoria.registrar(USUARIO, usuario.getId(), acao, antes, retrato(usuario));
    }

    /** Login excluído (ADR 0043): fica o último retrato. */
    void excluido(Usuario usuario, Map<String, Object> antes) {
        auditoria.registrar(USUARIO, usuario.getId(), "EXCLUIDO", antes, Map.of());
    }

    /** Ato sem campo a mostrar, como uma senha redefinida: fica quem fez e quando. */
    void ato(Usuario usuario, String acao) {
        auditoria.registrar(USUARIO, usuario.getId(), acao, Map.of(), Map.of());
    }
}
