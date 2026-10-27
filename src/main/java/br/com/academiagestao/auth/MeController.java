package br.com.academiagestao.auth;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class MeController {

    private final MeService service;

    MeController(MeService service) {
        this.service = service;
    }

    /** Quem sou eu: os dados do usuário dono do token. Qualquer perfil logado pode chamar. */
    @GetMapping("/api/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        MeService.Eu eu = service.eu(UUID.fromString(jwt.getSubject()));
        return new MeResponse(eu.usuario(), eu.precisaConfirmarEmail());
    }

    /** {@code precisaConfirmarEmail}: a tela pede a confirmação antes de mostrar o resto (ADR 0038). */
    record MeResponse(@JsonUnwrapped UsuarioResponse usuario, boolean precisaConfirmarEmail) {
    }
}
