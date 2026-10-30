package br.com.academiagestao.auth;

import java.util.UUID;

/**
 * O que a API mostra de um usuário. O hash da senha nunca sai daqui. O CPF (só da equipe, ADR 0047)
 * vai sem pontuação, e nulo em quem foi cadastrado antes dele.
 */
record UsuarioResponse(UUID id, String nome, String email, String cpf, Perfil perfil, boolean ativo,
                       boolean precisaTrocarSenha, boolean somenteLeitura) {

    static UsuarioResponse de(Usuario usuario) {
        return new UsuarioResponse(usuario.getId(), usuario.getNome(), usuario.getEmail(), usuario.getCpf(),
                usuario.getPerfil(), usuario.isAtivo(), usuario.isPrecisaTrocarSenha(), usuario.isSomenteLeitura());
    }
}
