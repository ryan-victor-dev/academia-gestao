package br.com.academiagestao.compartilhado;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * A página de erro do servlet ({@code /error}), para onde vai o que nenhum tratador pegou: uma
 * exceção inesperada num controller ou num filtro. No lugar do JSON padrão do Spring
 * (timestamp, status, error, path), o mesmo problem+json do resto da API (revisão de
 * 2026-10-17, B1). A mensagem da exceção não sai na resposta (pode ter detalhes internos);
 * o servidor já a registra no log, com a pilha.
 */
@RestController
@ConditionalOnWebApplication
class PaginaDeErro implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ProblemDetail> erro(HttpServletRequest requisicao) {
        HttpStatus status = requisicao.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer codigo
                && HttpStatus.resolve(codigo) != null ? HttpStatus.resolve(codigo) : HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problema = status.is5xxServerError()
                ? ProblemDetail.forStatusAndDetail(status, "Erro inesperado no servidor. Tente de novo; se continuar, "
                        + "avise o responsável pelo sistema.")
                : ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
        problema.setTitle(status.is5xxServerError() ? "Erro interno" : status.getReasonPhrase());
        if (requisicao.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String caminho) {
            problema.setInstance(URI.create(caminho));
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problema);
    }
}
