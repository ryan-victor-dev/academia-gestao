package br.com.academiagestao.treinos;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros do módulo de treinos. Vale só para os controllers deste pacote. */
@RestControllerAdvice(basePackageClasses = TreinosExceptionHandler.class)
class TreinosExceptionHandler {

    @ExceptionHandler(ExercicioNaoEncontradoException.class)
    ProblemDetail exercicioNaoEncontrado(ExercicioNaoEncontradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Exercício não encontrado");
        return problema;
    }

    @ExceptionHandler(NomeDeExercicioEmUsoException.class)
    ProblemDetail nomeEmUso(NomeDeExercicioEmUsoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Nome de exercício em uso");
        return problema;
    }

    @ExceptionHandler(FichaNaoEncontradaException.class)
    ProblemDetail fichaNaoEncontrada(FichaNaoEncontradaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Ficha não encontrada");
        return problema;
    }

    @ExceptionHandler(FichaRecusadaException.class)
    ProblemDetail fichaRecusada(FichaRecusadaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle(e.titulo());
        return problema;
    }
}
