package br.com.academiagestao.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AutenticacaoService autenticacaoService;

    AuthController(AutenticacaoService autenticacaoService) {
        this.autenticacaoService = autenticacaoService;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest requisicao) {
        return LoginResponse.de(autenticacaoService.autenticar(request.email(), request.senha(),
                requisicao.getRemoteAddr()));
    }

    /**
     * Troca a senha do próprio usuário. É a única rota (além de /api/me) liberada para
     * quem entrou com senha temporária. Devolve um token novo, já sem a restrição.
     */
    @PostMapping("/trocar-senha")
    LoginResponse trocarSenha(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TrocaDeSenhaRequest request) {
        return LoginResponse.de(autenticacaoService.trocarSenha(
                UUID.fromString(jwt.getSubject()), request.senhaAtual(), request.novaSenha()));
    }

    // Mesmo tamanho máximo do e-mail cadastrado: e-mail maior não existe, e é o que vai
    // para a tabela do limite de tentativas.
    record LoginRequest(@NotBlank @Email @Size(max = 150) String email, @NotBlank String senha) {
    }

    record TrocaDeSenhaRequest(@NotBlank(message = "é obrigatório") String senhaAtual,
                               @NotBlank(message = "é obrigatório") String novaSenha) {
    }

    record LoginResponse(String token, String tipo, Instant expiraEm, boolean precisaTrocarSenha) {

        static LoginResponse de(GeradorDeToken.TokenEmitido emitido) {
            return new LoginResponse(emitido.token(), "Bearer", emitido.expiraEm(), emitido.precisaTrocarSenha());
        }
    }
}
