package br.com.academiagestao.compartilhado;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Erros comuns a toda a API (JSON malformado, método não suportado...) em ProblemDetail.
 * Substitui o tratador padrão do Spring Boot para incluir, nos erros de validação,
 * qual campo falhou e por quê.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ErroDeCampo> erros = e.getBindingResult().getFieldErrors().stream()
                .map(erro -> new ErroDeCampo(erro.getField(), erro.getDefaultMessage()))
                .toList();

        ProblemDetail problema = e.getBody();
        problema.setTitle("Dados inválidos");
        problema.setDetail("Um ou mais campos estão inválidos.");
        problema.setProperty("erros", erros);
        return handleExceptionInternal(e, problema, headers, status, request);
    }

    // Validação de parâmetros da URL, como ?tamanho=500.
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<ErroDeCampo> erros = e.getParameterValidationResults().stream()
                .flatMap(resultado -> resultado.getResolvableErrors().stream()
                        .map(erro -> new ErroDeCampo(resultado.getMethodParameter().getParameterName(),
                                erro.getDefaultMessage())))
                .toList();

        ProblemDetail problema = e.getBody();
        problema.setTitle("Dados inválidos");
        problema.setDetail("Um ou mais parâmetros estão inválidos.");
        problema.setProperty("erros", erros);
        return handleExceptionInternal(e, problema, headers, status, request);
    }

    record ErroDeCampo(String campo, String mensagem) {
    }
}
