package br.com.academiagestao.auth;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class MeController {

    private final UsuarioRepository repository;

    MeController(UsuarioRepository repository) {
        this.repository = repository;
    }

    /** Quem sou eu: os dados do usuário dono do token. Qualquer perfil logado pode chamar. */
    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    UsuarioResponse me(@AuthenticationPrincipal Jwt jwt) {
        // O token pode ter sido emitido antes de o usuário ser desativado ou removido.
        return repository.findById(UUID.fromString(jwt.getSubject()))
                .filter(Usuario::isAtivo)
                .map(UsuarioResponse::de)
                .orElseThrow(() -> new DisabledException("Usuário inativo ou removido"));
    }
}
