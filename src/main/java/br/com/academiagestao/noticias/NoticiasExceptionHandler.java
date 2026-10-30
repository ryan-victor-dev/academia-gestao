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
}
