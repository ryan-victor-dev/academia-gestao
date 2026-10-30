package br.com.academiagestao.auth;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
class MeController {

    private final MeService service;
    private final EquipeService equipe;

    MeController(MeService service, EquipeService equipe) {
        this.service = service;
        this.equipe = equipe;
    }

    /** Quem sou eu: os dados do usuário dono do token. Qualquer perfil logado pode chamar. */
    @GetMapping("/api/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        MeService.Eu eu = service.eu(UUID.fromString(jwt.getSubject()));
        return new MeResponse(eu.usuario(), eu.precisaConfirmarEmail());
    }

    /**
     * A própria data de nascimento, para quem é da equipe (ADR 0048): quem é de antes dela informa por aqui. A do
     * aluno fica no cadastro, e quem corrige é a recepção.
     */
    @PutMapping("/api/me/nascimento")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR')")
    UsuarioResponse informarMeuNascimento(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NascimentoRequest pedido) {
        return equipe.informarMeuNascimento(UUID.fromString(jwt.getSubject()), pedido.dataNascimento());
    }

    /** {@code precisaConfirmarEmail}: a tela pede a confirmação antes de mostrar o resto (ADR 0038). */
    record MeResponse(@JsonUnwrapped UsuarioResponse usuario, boolean precisaConfirmarEmail) {
    }

    record NascimentoRequest(@NotNull(message = "é obrigatório") LocalDate dataNascimento) {
    }
}
