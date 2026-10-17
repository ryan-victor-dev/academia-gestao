package br.com.academiagestao.planos;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros do módulo de planos. Vale só para os controllers deste pacote. */
@RestControllerAdvice(basePackageClasses = PlanosExceptionHandler.class)
class PlanosExceptionHandler {

    @ExceptionHandler(PlanoNaoEncontradoException.class)
    ProblemDetail naoEncontrado(PlanoNaoEncontradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Plano não encontrado");
        return problema;
    }

    @ExceptionHandler(NomeDePlanoEmUsoException.class)
    ProblemDetail nomeEmUso(NomeDePlanoEmUsoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Nome de plano em uso");
        return problema;
    }
}
