package br.com.academiagestao.noticias;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros do módulo de notícias. Vale só para os controllers deste pacote. */
@RestControllerAdvice(basePackageClasses = NoticiasExceptionHandler.class)
class NoticiasExceptionHandler {

    @ExceptionHandler(PublicacaoNaoEncontradaException.class)
    ProblemDetail naoEncontrada(PublicacaoNaoEncontradaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Publicação não encontrada");
        return problema;
    }

    @ExceptionHandler(AnexoNaoEncontradoException.class)
    ProblemDetail anexoNaoEncontrado(AnexoNaoEncontradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Anexo não encontrado");
        return problema;
    }

    @ExceptionHandler(AnexoRecusadoException.class)
    ProblemDetail anexoRecusado(AnexoRecusadoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problema.setTitle(e.titulo());
        return problema;
    }
}
