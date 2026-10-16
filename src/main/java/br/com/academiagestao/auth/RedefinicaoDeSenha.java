package br.com.academiagestao.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** "Esqueci minha senha": gera uma nova senha temporária para outra pessoa. */
@Service
class RedefinicaoDeSenha {

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;

    RedefinicaoDeSenha(UsuarioRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Pelo ADMIN, para qualquer usuário que não seja ADMIN. Como só ADMIN chama, isso
     * também impede alguém de redefinir a própria senha por aqui.
     */
    @Transactional
    CredenciaisTemporarias peloAdmin(UUID usuarioId) {
        Usuario usuario = repository.findById(usuarioId)
                .orElseThrow(() -> new UsuarioNaoEncontradoException("Usuário não encontrado"));
        if (usuario.getPerfil() == Perfil.ADMIN) {
            throw new RedefinicaoNaoPermitidaException();
        }
        return aplicar(usuario);
    }

    /**
     * Gera a senha, grava só o hash, exige a troca no próximo acesso e derruba todas as
     * sessões abertas (quem tinha a senha antiga não continua entrando).
     */
    CredenciaisTemporarias aplicar(Usuario usuario) {
        if (!usuario.isAtivo()) {
            throw new UsuarioInativoException();
        }
        String senhaTemporaria = GeradorDeSenhaTemporaria.gerar();
        usuario.redefinirComSenhaTemporaria(passwordEncoder.encode(senhaTemporaria));
        return new CredenciaisTemporarias(usuario.getEmail(), senhaTemporaria);
    }
}
