package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.CobrancaInvalidaException;
import br.com.academiagestao.cobranca.dominio.PagamentoRecusadoException;
import br.com.academiagestao.cobranca.dominio.TransicaoInvalidaException;
import br.com.academiagestao.cobranca.dominio.ValorDevido;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Erros do módulo de cobrança. Vale só para os controllers deste pacote. */
@RestControllerAdvice(basePackageClasses = CobrancaExceptionHandler.class)
class CobrancaExceptionHandler {

    @ExceptionHandler(CobrancaInvalidaException.class)
    ProblemDetail invalida(CobrancaInvalidaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Dados inválidos");
        return problema;
    }

    @ExceptionHandler(CobrancaNaoEncontradaException.class)
    ProblemDetail naoEncontrada(CobrancaNaoEncontradaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Cobrança não encontrada");
        return problema;
    }

    /** Leva o devido de hoje: quem abriu a tela na véspera vê o valor atual e paga certo. */
    @ExceptionHandler(PagamentoRecusadoException.class)
    ProblemDetail pagamentoRecusado(PagamentoRecusadoException e) {
        ValorDevido devido = e.getDevido();
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "O valor devido hoje é " + devido.total() + "; registre o pagamento com esse valor");
        problema.setTitle("Valor diferente do devido");
        problema.setProperty("devidoHoje", Map.of("valor", devido.valor(), "acrescimo", devido.acrescimo(),
                "juros", devido.juros(), "total", devido.total(), "diasDeAtraso", devido.diasDeAtraso()));
        return problema;
    }

    @ExceptionHandler(TransicaoInvalidaException.class)
    ProblemDetail transicaoInvalida(TransicaoInvalidaException e) {
        String detalhe = switch (e.getOrigem()) {
            case PAGA -> "A cobrança já está paga" + (e.getDestino().name().equals("CANCELADA")
                    ? "; estorne o pagamento antes de cancelar" : "");
            case CANCELADA -> "A cobrança está cancelada";
            case PENDENTE -> "A cobrança está em aberto: não há pagamento para estornar";
        };
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detalhe);
        problema.setTitle("Operação não permitida nesta cobrança");
        return problema;
    }

    /** Alterada por outra operação no meio (rara: as operações travam a cobrança antes). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail alteradaNoMeio(ObjectOptimisticLockingFailureException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A cobrança foi alterada por outra operação; consulte de novo");
        problema.setTitle("Cobrança alterada");
        return problema;
    }
}
