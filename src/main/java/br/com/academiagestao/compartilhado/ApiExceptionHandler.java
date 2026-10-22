package br.com.academiagestao.compartilhado;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Erros comuns a toda a API (JSON malformado, método não suportado...) em ProblemDetail.
 * Substitui o tratador padrão do Spring Boot para incluir, nos erros de validação,
 * qual campo falhou e por quê. Os textos dos erros gerados pelo próprio Spring vêm
 * do messages.properties.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TENTAR_DE_NOVO_EM_SEGUNDOS = "5";

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

    // Corpo enviado em partes que passou do limite no meio da leitura (ver LimiteDeTamanhoDoCorpo).
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof CorpoGrandeDemaisException grande) {
                ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                        grande.getMessage());
                problema.setTitle("Corpo grande demais");
                return handleExceptionInternal(e, problema, headers, HttpStatus.CONTENT_TOO_LARGE, request);
            }
            if (causa instanceof CaractereDeControleException controle) {
                return handleExceptionInternal(e, caractereDeControle(controle), headers, HttpStatus.BAD_REQUEST,
                        request);
            }
        }
        return super.handleHttpMessageNotReadable(e, headers, status, request);
    }

    // Parâmetro ou cabeçalho com caractere de controle (ver TextoNaUrlSemCaractereDeControle).
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        if (e.getMostSpecificCause() instanceof CaractereDeControleException controle) {
            return handleExceptionInternal(e, caractereDeControle(controle), headers, HttpStatus.BAD_REQUEST, request);
        }
        return super.handleTypeMismatch(e, headers, status, request);
    }

    private static ProblemDetail caractereDeControle(CaractereDeControleException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Dados inválidos");
        return problema;
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

    @ExceptionHandler(RecursoModificadoException.class)
    ProblemDetail recursoModificado(RecursoModificadoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, e.getMessage());
        problema.setTitle(e.titulo());
        return problema;
    }

    @ExceptionHandler(IfMatchAusenteException.class)
    ProblemDetail ifMatchAusente(IfMatchAusenteException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED, e.getMessage());
        problema.setTitle("Cabeçalho If-Match obrigatório");
        return problema;
    }

    // Regras que só o domínio confere (ex.: telefone sem DDD).
    @ExceptionHandler(DadosInvalidosException.class)
    ProblemDetail dadosInvalidos(DadosInvalidosException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Dados inválidos");
        return problema;
    }

    /*
     * Banco sem conexão livre em 3 s, ou consulta que passou de 5 s (ver application.yaml).
     * É passageiro: 503 com Retry-After, em vez de um 500 que parece defeito. O detalhe do
     * erro (SQL, nome da trava) fica só no log.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> bancoSobrecarregado(Exception e) {
        LOG.warn("Banco sobrecarregado: {}", e.getMessage());
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "O sistema está sobrecarregado no momento. Tente de novo em alguns segundos.");
        problema.setTitle("Sistema sobrecarregado");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, TENTAR_DE_NOVO_EM_SEGUNDOS)
                .body(problema);
    }

    record ErroDeCampo(String campo, String mensagem) {
    }
}
