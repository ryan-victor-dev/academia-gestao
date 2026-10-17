package br.com.academiagestao.matriculas;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros do módulo de matrículas. Vale só para os controllers deste pacote. */
@RestControllerAdvice(basePackageClasses = MatriculasExceptionHandler.class)
class MatriculasExceptionHandler {

    @ExceptionHandler(MatriculaNaoEncontradaException.class)
    ProblemDetail naoEncontrada(MatriculaNaoEncontradaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Matrícula não encontrada");
        return problema;
    }

    @ExceptionHandler(MatriculaRecusadaException.class)
    ProblemDetail recusada(MatriculaRecusadaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle(e.titulo());
        return problema;
    }

    @ExceptionHandler(MatriculaNaoCancelavelException.class)
    ProblemDetail naoCancelavel(MatriculaNaoCancelavelException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Matrícula não pode ser cancelada");
        return problema;
    }
}
