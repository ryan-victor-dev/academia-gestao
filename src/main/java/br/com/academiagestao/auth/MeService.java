package br.com.academiagestao.auth;

import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Quem sou eu: os dados do usuário dono do token. */
@Service
class MeService {

    private final UsuarioRepository repository;
    private final ConfirmacaoDeEmail confirmacao;

    MeService(UsuarioRepository repository, ConfirmacaoDeEmail confirmacao) {
        this.repository = repository;
        this.confirmacao = confirmacao;
    }

    @Transactional(readOnly = true)
    Eu eu(UUID usuarioId) {
        // O token pode ter sido emitido antes de o usuário ser desativado ou removido.
        return repository.findById(usuarioId)
                .filter(Usuario::isAtivo)
                .map(u -> new Eu(UsuarioResponse.de(u), confirmacao.exigida(u)))
                .orElseThrow(() -> new DisabledException("Usuário inativo ou removido"));
    }

    record Eu(UsuarioResponse usuario, boolean precisaConfirmarEmail) {
    }
}
