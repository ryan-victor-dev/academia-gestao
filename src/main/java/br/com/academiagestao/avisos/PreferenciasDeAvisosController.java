package br.com.academiagestao.avisos;

import br.com.academiagestao.auth.ClaimsDoToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A própria pessoa liga ou desliga os avisos da academia (eventos e comunicados, ADR 0047, decisão 8). Qualquer perfil;
 * o id vem do token. O aviso de fatura não se desliga. O aluno inativo (só de leitura, ADR 0029) consulta, mas não muda
 * (e ele já não recebe: só ativos são avisados).
 */
@RestController
class PreferenciasDeAvisosController {

    private final PreferenciasDeAvisos preferencias;

    PreferenciasDeAvisosController(PreferenciasDeAvisos preferencias) {
        this.preferencias = preferencias;
    }

    @GetMapping("/api/avisos/preferencias")
    PreferenciaResponse minha(@AuthenticationPrincipal Jwt jwt) {
        return new PreferenciaResponse(preferencias.recebe(dono(jwt)));
    }

    @PutMapping("/api/avisos/preferencias")
    PreferenciaResponse mudar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PreferenciaRequest pedido) {
        return new PreferenciaResponse(preferencias.mudar(dono(jwt), pedido.recebeAvisosDaAcademia()));
    }

    private static PreferenciasDeAvisos.Dono dono(Jwt jwt) {
        String alunoId = jwt.getClaimAsString(ClaimsDoToken.ALUNO_ID);
        return alunoId != null ? new PreferenciasDeAvisos.Dono(UUID.fromString(alunoId), null)
                : new PreferenciasDeAvisos.Dono(null, UUID.fromString(jwt.getSubject()));
    }

    record PreferenciaRequest(@NotNull(message = "é obrigatório") Boolean recebeAvisosDaAcademia) {
    }

    record PreferenciaResponse(boolean recebeAvisosDaAcademia) {
    }
}
