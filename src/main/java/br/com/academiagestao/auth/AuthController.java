package br.com.academiagestao.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private static final String CAMINHO_DO_COOKIE = "/api/auth";

    private final AutenticacaoService autenticacaoService;
    private final Sessoes sessoes;

    AuthController(AutenticacaoService autenticacaoService, Sessoes sessoes) {
        this.autenticacaoService = autenticacaoService;
        this.sessoes = sessoes;
    }

    /** Devolve o token e abre uma sessão renovável (cookie), ADR 0033. */
    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest requisicao) {
        return comSessaoNova(autenticacaoService.autenticar(request.email(), request.senha(),
                requisicao.getRemoteAddr()));
    }

    /** Troca o cookie da sessão por um token novo (e um cookie novo). */
    @PostMapping("/renovar")
    ResponseEntity<LoginResponse> renovar(@CookieValue(name = Sessoes.COOKIE, required = false) String cookie) {
        Sessoes.Renovada renovada = sessoes.renovar(cookie);
        ResponseEntity.BodyBuilder resposta = ResponseEntity.ok();
        renovada.cookie().ifPresent(novo -> resposta.header(HttpHeaders.SET_COOKIE, cookie(novo).toString()));
        return resposta.body(LoginResponse.de(renovada.token(), renovada.sessaoExpiraEm()));
    }

    /** Encerra a sessão do cookie e o apaga do navegador. */
    @PostMapping("/sair")
    ResponseEntity<Void> sair(@CookieValue(name = Sessoes.COOKIE, required = false) String cookie) {
        sessoes.encerrar(cookie);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookieApagado().toString()).build();
    }

    /**
     * Troca a senha do próprio usuário. É a única rota (além de /api/me) liberada para
     * quem entrou com senha temporária. Devolve um token novo, já sem a restrição.
     */
    @PostMapping("/trocar-senha")
    ResponseEntity<LoginResponse> trocarSenha(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody TrocaDeSenhaRequest request,
                                              HttpServletRequest requisicao) {
        // A versão do token mudou: a sessão antiga não renova mais, e uma nova é aberta.
        return comSessaoNova(autenticacaoService.trocarSenha(
                UUID.fromString(jwt.getSubject()), request.senhaAtual(), request.novaSenha(),
                requisicao.getRemoteAddr()));
    }

    private ResponseEntity<LoginResponse> comSessaoNova(GeradorDeToken.TokenEmitido emitido) {
        Sessoes.Aberta sessao = sessoes.abrir(emitido.usuarioId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(sessao.cookie()).toString())
                .body(LoginResponse.de(emitido, sessao.expiraEm()));
    }

    /** HttpOnly (script nenhum lê), Secure, SameSite=Strict e só nas rotas de sessão. */
    private static ResponseCookie cookie(Sessoes.Cookie cookie) {
        return ResponseCookie.from(Sessoes.COOKIE, cookie.valor())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(CAMINHO_DO_COOKIE)
                .maxAge(cookie.validade())
                .build();
    }

    static ResponseCookie cookieApagado() {
        return ResponseCookie.from(Sessoes.COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(CAMINHO_DO_COOKIE)
                .maxAge(0)
                .build();
    }

    // Mesmo tamanho máximo do e-mail cadastrado: e-mail maior não existe, e é o que vai
    // para a tabela do limite de tentativas.
    record LoginRequest(@NotBlank @Email @Size(max = 150) String email, @NotBlank String senha) {
    }

    record TrocaDeSenhaRequest(@NotBlank(message = "é obrigatório") String senhaAtual,
                               @NotBlank(message = "é obrigatório") String novaSenha) {
    }

    /** {@code expiraEm}: do token (15 min); {@code sessaoExpiraEm}: até quando dá para renovar. */
    record LoginResponse(String token, String tipo, Instant expiraEm, boolean precisaTrocarSenha,
                         boolean precisaConfirmarEmail, Instant sessaoExpiraEm) {

        static LoginResponse de(GeradorDeToken.TokenEmitido emitido, Instant sessaoExpiraEm) {
            return new LoginResponse(emitido.token(), "Bearer", emitido.expiraEm(), emitido.precisaTrocarSenha(),
                    emitido.precisaConfirmarEmail(), sessaoExpiraEm);
        }
    }
}
