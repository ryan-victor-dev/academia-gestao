package br.com.academiagestao.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AutenticacaoService autenticacaoService;

    AuthController(AutenticacaoService autenticacaoService) {
        this.autenticacaoService = autenticacaoService;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        GeradorDeToken.TokenEmitido emitido = autenticacaoService.autenticar(request.email(), request.senha());
        return new LoginResponse(emitido.token(), "Bearer", emitido.expiraEm());
    }

    record LoginRequest(@NotBlank @Email String email, @NotBlank String senha) {
    }

    record LoginResponse(String token, String tipo, Instant expiraEm) {
    }
}
