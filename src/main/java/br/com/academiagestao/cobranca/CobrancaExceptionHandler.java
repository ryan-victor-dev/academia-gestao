package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.CobrancaInvalidaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros do módulo de cobrança. Vale só para os controllers deste pacote. */
@RestControllerAdvice(basePackageClasses = CobrancaExceptionHandler.class)
class CobrancaExceptionHandler {

    @ExceptionHandler(CobrancaInvalidaException.class)
    ProblemDetail invalida(CobrancaInvalidaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Dados inválidos");
        return problema;
    }
}
