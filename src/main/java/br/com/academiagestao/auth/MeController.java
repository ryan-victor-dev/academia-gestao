package br.com.academiagestao.auth;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
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
    private final ConfirmacaoDeEmail confirmacao;

    MeController(UsuarioRepository repository, ConfirmacaoDeEmail confirmacao) {
        this.repository = repository;
        this.confirmacao = confirmacao;
    }

    /** Quem sou eu: os dados do usuário dono do token. Qualquer perfil logado pode chamar. */
    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        // O token pode ter sido emitido antes de o usuário ser desativado ou removido.
        return repository.findById(UUID.fromString(jwt.getSubject()))
                .filter(Usuario::isAtivo)
                .map(u -> new MeResponse(UsuarioResponse.de(u), confirmacao.exigida(u)))
                .orElseThrow(() -> new DisabledException("Usuário inativo ou removido"));
    }

    /** {@code precisaConfirmarEmail}: a tela pede a confirmação antes de mostrar o resto (ADR 0038). */
    record MeResponse(@JsonUnwrapped UsuarioResponse usuario, boolean precisaConfirmarEmail) {
    }
}
