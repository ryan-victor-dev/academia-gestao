package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.IdentidadeInformada;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Identidade de gênero e orientação sexual da equipe (ADR 0047, decisão 2): dado sensível, só o ADMIN e a
 * própria pessoa veem. A lista da equipe ({@code GET /api/usuarios}) não traz esses campos. As respostas não
 * ficam em cache.
 */
@RestController
class IdentidadeDaEquipeController {

    private final IdentidadeDaEquipeService service;

    IdentidadeDaEquipeController(IdentidadeDaEquipeService service) {
        this.service = service;
    }

    /** A própria: o id vem do token. O aluno usa a do cadastro ({@code /api/alunos/me/identidade}). */
    @GetMapping("/api/me/identidade")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR')")
    ResponseEntity<IdentidadeInformada> minha(@AuthenticationPrincipal Jwt jwt) {
        return semCache(IdentidadeInformada.de(service.buscar(UUID.fromString(jwt.getSubject()))));
    }

    @PutMapping("/api/me/identidade")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR')")
    ResponseEntity<IdentidadeInformada> informarAMinha(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestBody IdentidadeInformada pedido) {
        return semCache(IdentidadeInformada.de(service.gravar(UUID.fromString(jwt.getSubject()), pedido.paraIdentidade())));
    }

    @GetMapping("/api/usuarios/{id}/identidade")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<IdentidadeInformada> deAlguem(@PathVariable UUID id) {
        return semCache(IdentidadeInformada.de(service.buscar(id)));
    }

    @PutMapping("/api/usuarios/{id}/identidade")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<IdentidadeInformada> informarDeAlguem(@PathVariable UUID id, @RequestBody IdentidadeInformada pedido) {
        return semCache(IdentidadeInformada.de(service.gravar(id, pedido.paraIdentidade())));
    }

    private static ResponseEntity<IdentidadeInformada> semCache(IdentidadeInformada corpo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(corpo);
    }
}
