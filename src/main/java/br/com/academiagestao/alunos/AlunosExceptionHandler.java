package br.com.academiagestao.alunos;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros do módulo de alunos. Vale só para os controllers deste pacote. */
@RestControllerAdvice(basePackageClasses = AlunosExceptionHandler.class)
class AlunosExceptionHandler {

    @ExceptionHandler(AlunoNaoEncontradoException.class)
    ProblemDetail naoEncontrado(AlunoNaoEncontradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Aluno não encontrado");
        return problema;
    }

    @ExceptionHandler(AlunoModificadoException.class)
    ProblemDetail alunoModificado(AlunoModificadoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, e.getMessage());
        problema.setTitle("Aluno alterado por outra pessoa");
        return problema;
    }

    @ExceptionHandler(IfMatchAusenteException.class)
    ProblemDetail ifMatchAusente(IfMatchAusenteException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED, e.getMessage());
        problema.setTitle("Cabeçalho If-Match obrigatório");
        return problema;
    }

    @ExceptionHandler(AlunoInativoException.class)
    ProblemDetail alunoInativo(AlunoInativoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Aluno inativo");
        return problema;
    }

    @ExceptionHandler(CpfJaCadastradoException.class)
    ProblemDetail cpfJaCadastrado(CpfJaCadastradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("CPF já cadastrado");
        return problema;
    }
}
