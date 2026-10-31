package br.com.academiagestao.folha;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros do módulo da folha. Vale só para os controllers deste pacote. */
@RestControllerAdvice(basePackageClasses = FolhaExceptionHandler.class)
class FolhaExceptionHandler {

    @ExceptionHandler(PessoaNaoEncontradaException.class)
    ProblemDetail naoEncontrada(PessoaNaoEncontradaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Pessoa não encontrada");
        return problema;
    }

    @ExceptionHandler(PessoaRepetidaException.class)
    ProblemDetail repetida(PessoaRepetidaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Pessoa já está na folha");
        return problema;
    }
}
