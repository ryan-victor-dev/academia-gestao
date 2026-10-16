package br.com.academiagestao.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
class AutenticacaoService {

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final GeradorDeToken geradorDeToken;

    // Hash de uma senha que ninguém conhece. Quando o e-mail não existe, comparamos a senha
    // com ele mesmo assim, para a resposta demorar o mesmo tanto que com um e-mail válido.
    // Sem isso, medir o tempo de resposta revelaria quais e-mails estão cadastrados.
    private final String hashFalso;

    AutenticacaoService(UsuarioRepository repository,
                        PasswordEncoder passwordEncoder,
                        GeradorDeToken geradorDeToken) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.geradorDeToken = geradorDeToken;
        this.hashFalso = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    GeradorDeToken.TokenEmitido autenticar(String email, String senha) {
        Optional<Usuario> usuario = repository.findByEmail(Usuario.normalizarEmail(email));

        // A senha é sempre conferida, mesmo para usuário inexistente ou inativo (mesmo tempo).
        String hash = usuario.map(Usuario::getSenhaHash).orElse(hashFalso);
        boolean senhaConfere = passwordEncoder.matches(senha, hash);

        if (usuario.isEmpty() || !senhaConfere || !usuario.get().isAtivo()) {
            throw new CredenciaisInvalidasException();
        }
        return geradorDeToken.gerar(usuario.get());
    }
}
