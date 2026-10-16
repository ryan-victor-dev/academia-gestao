package br.com.academiagestao.auth;

import java.util.UUID;

/** O que a API mostra de um usuário. O hash da senha nunca sai daqui. */
record UsuarioResponse(UUID id, String nome, String email, Perfil perfil, boolean ativo,
                       boolean precisaTrocarSenha) {

    static UsuarioResponse de(Usuario usuario) {
        return new UsuarioResponse(usuario.getId(), usuario.getNome(), usuario.getEmail(),
                usuario.getPerfil(), usuario.isAtivo(), usuario.isPrecisaTrocarSenha());
    }
}
