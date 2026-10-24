package br.com.academiagestao.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A confirmação do e-mail no primeiro acesso (ADR 0038). São as únicas rotas, além de /api/me,
 * de quem ainda não confirmou. Confirmado, a tela renova o token (POST /api/auth/renovar), que
 * sai sem a restrição.
 */
@RestController
@RequestMapping("/api/auth/email")
class EmailController {

    private final ConfirmacaoDeEmail confirmacao;

    EmailController(ConfirmacaoDeEmail confirmacao) {
        this.confirmacao = confirmacao;
    }

    /** Manda (de novo) o código para o e-mail do login. */
    @PostMapping("/codigo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void enviarCodigo(@AuthenticationPrincipal Jwt jwt) {
        confirmacao.enviarCodigo(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/confirmar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfirmacaoRequest request) {
        confirmacao.confirmar(UUID.fromString(jwt.getSubject()), request.codigo());
    }

    /** O e-mail estava errado: corrige e manda o código para o novo. */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void corrigir(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CorrecaoRequest request) {
        confirmacao.corrigir(UUID.fromString(jwt.getSubject()), request.email());
    }

    record ConfirmacaoRequest(@NotBlank(message = "é obrigatório") String codigo) {
    }

    // Mesmo tamanho do e-mail cadastrado.
    record CorrecaoRequest(@NotBlank(message = "é obrigatório") @Email(message = "não é um e-mail válido")
                           @Size(max = 150) String email) {
    }
}
