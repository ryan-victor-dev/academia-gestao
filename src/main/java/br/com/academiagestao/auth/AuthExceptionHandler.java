package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.CorreioIndisponivelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;

@RestControllerAdvice
class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    // Mesma resposta para e-mail inexistente, senha errada e usuário inativo.
    @ExceptionHandler(CredenciaisInvalidasException.class)
    ProblemDetail credenciaisInvalidas() {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos.");
        problema.setTitle("Credenciais inválidas");
        return problema;
    }

    // Mesma resposta para qualquer motivo, e o cookie da sessão apagado do navegador.
    @ExceptionHandler(SessaoInvalidaException.class)
    ResponseEntity<ProblemDetail> sessaoInvalida() {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Sessão encerrada ou vencida: entre de novo.");
        problema.setTitle("Sessão inválida");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, AuthController.cookieApagado().toString())
                .body(problema);
    }

    // 429 com Retry-After: o cliente sabe quantos segundos esperar.
    @ExceptionHandler(MuitasTentativasException.class)
    ResponseEntity<ProblemDetail> muitasTentativas(MuitasTentativasException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problema.setTitle("Muitas tentativas");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(segundos(e.esperar())))
                .body(problema);
    }

    @ExceptionHandler(MuitosCodigosException.class)
    ResponseEntity<ProblemDetail> muitosCodigos(MuitosCodigosException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problema.setTitle("Muitos códigos pedidos");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(segundos(e.esperar()))).body(problema);
    }

    @ExceptionHandler(CodigoInvalidoException.class)
    ProblemDetail codigoInvalido(CodigoInvalidoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Código inválido");
        return problema;
    }

    /** O detalhe fica no log; a resposta só diz para tentar depois (ADR 0038). */
    @ExceptionHandler(CorreioIndisponivelException.class)
    ProblemDetail correioIndisponivel(CorreioIndisponivelException e) {
        log.warn("Servidor de e-mail indisponível: {}", e.getMessage());
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível enviar o e-mail agora. Tente de novo em alguns minutos.");
        problema.setTitle("E-mail indisponível");
        return problema;
    }

    /** O teto de senhas ao mesmo tempo (ADR 0046, item 3): passageiro, como o banco sobrecarregado. */
    @ExceptionHandler(MuitasSenhasAoMesmoTempoException.class)
    ResponseEntity<ProblemDetail> muitasSenhas(MuitasSenhasAoMesmoTempoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problema.setTitle("Sistema ocupado");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problema);
    }

    @ExceptionHandler(MuitasRequisicoesException.class)
    ResponseEntity<ProblemDetail> muitasRequisicoes(MuitasRequisicoesException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problema.setTitle("Muitas requisições");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(segundos(e.esperar())))
                .body(problema);
    }

    @ExceptionHandler(LoginJaExisteException.class)
    ProblemDetail loginJaExiste(LoginJaExisteException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Login já existe");
        return problema;
    }

    @ExceptionHandler(UsuarioNaoEncontradoException.class)
    ProblemDetail usuarioNaoEncontrado(UsuarioNaoEncontradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Login não encontrado");
        return problema;
    }

    @ExceptionHandler(UsuarioInativoException.class)
    ProblemDetail usuarioInativo(UsuarioInativoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Usuário inativo");
        return problema;
    }

    @ExceptionHandler(OperacaoNaoPermitidaException.class)
    ProblemDetail operacaoNaoPermitida(OperacaoNaoPermitidaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Operação não permitida");
        return problema;
    }

    @ExceptionHandler(RedefinicaoNaoPermitidaException.class)
    ProblemDetail redefinicaoNaoPermitida(RedefinicaoNaoPermitidaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problema.setTitle("Redefinição não permitida");
        return problema;
    }

    // O detalhe do erro do token (expirado, assinatura inválida...) não vai no corpo:
    // ajuda pouco o cliente legítimo e muito quem está tentando forjar um token.
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail naoAutenticado() {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Token de acesso ausente, inválido ou expirado.");
        problema.setTitle("Não autenticado");
        return problema;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail semPermissao() {
        if (tem(SegurancaConfig.SENHA_TEMPORARIA)) {
            ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                    "Troque a senha temporária em POST /api/auth/trocar-senha antes de continuar.");
            problema.setTitle("Troca de senha obrigatória");
            return problema;
        }
        if (tem(SegurancaConfig.EMAIL_NAO_CONFIRMADO)) {
            ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                    "Confirme o seu e-mail com o código enviado (POST /api/auth/email/confirmar) antes de continuar.");
            problema.setTitle("Confirmação de e-mail obrigatória");
            return problema;
        }
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Seu perfil não tem permissão para este recurso.");
        problema.setTitle("Acesso negado");
        return problema;
    }

    // Arredonda para cima, nunca menos de 1: "Retry-After: 0" faria o cliente insistir na hora.
    private static long segundos(Duration esperar) {
        return Math.max(1, (esperar.toMillis() + 999) / 1000);
    }

    private static boolean tem(String autoridade) {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        return autenticacao != null && autenticacao.getAuthorities().stream()
                .anyMatch(a -> autoridade.equals(a.getAuthority()));
    }
}
