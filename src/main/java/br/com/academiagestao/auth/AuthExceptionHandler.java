package br.com.academiagestao.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class AuthExceptionHandler {

    // Mesma resposta para e-mail inexistente, senha errada e usuário inativo.
    @ExceptionHandler(CredenciaisInvalidasException.class)
    ProblemDetail credenciaisInvalidas() {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos.");
        problema.setTitle("Credenciais inválidas");
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
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Seu perfil não tem permissão para este recurso.");
        problema.setTitle("Acesso negado");
        return problema;
    }
}
