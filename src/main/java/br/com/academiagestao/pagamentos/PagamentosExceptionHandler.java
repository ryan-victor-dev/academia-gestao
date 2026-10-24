package br.com.academiagestao.pagamentos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = PagamentosExceptionHandler.class)
class PagamentosExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PagamentosExceptionHandler.class);

    @ExceptionHandler(PagamentoOnlineInvalidoException.class)
    ProblemDetail invalido(PagamentoOnlineInvalidoException e) {
        return problema(HttpStatus.BAD_REQUEST, "Dados inválidos", e.getMessage());
    }

    @ExceptionHandler(PagamentoOnlineNaoEncontradoException.class)
    ProblemDetail naoEncontrado(PagamentoOnlineNaoEncontradoException e) {
        return problema(HttpStatus.NOT_FOUND, "Não encontrado", e.getMessage());
    }

    @ExceptionHandler(PagamentoOnlineRecusadoException.class)
    ProblemDetail recusado(PagamentoOnlineRecusadoException e) {
        return problema(HttpStatus.CONFLICT, "Pagamento online recusado", e.getMessage());
    }

    /** O detalhe da empresa fica no log; a resposta só diz para tentar depois. */
    @ExceptionHandler(ProvedorIndisponivelException.class)
    ProblemDetail indisponivel(ProvedorIndisponivelException e) {
        log.warn("Empresa de pagamentos indisponível: {}", e.getMessage());
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "Pagamento online indisponível",
                "A empresa de pagamentos não respondeu. Tente de novo em alguns minutos.");
    }

    /** Sem dizer o que faltou: quem não é a empresa não aprende como forjar um aviso. */
    @ExceptionHandler(AvisoRecusadoException.class)
    ProblemDetail avisoRecusado(AvisoRecusadoException e) {
        log.warn("Aviso de pagamento recusado: {}", e.getMessage());
        return problema(HttpStatus.UNAUTHORIZED, "Aviso recusado", "Aviso recusado");
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(titulo);
        return problema;
    }
}
