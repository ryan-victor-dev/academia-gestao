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

    @ExceptionHandler(CpfJaCadastradoException.class)
    ProblemDetail cpfJaCadastrado(CpfJaCadastradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("CPF já cadastrado");
        return problema;
    }

    // Regras que só a entidade Aluno confere (ex.: telefone sem DDD).
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail dadoInvalido(IllegalArgumentException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Dados inválidos");
        return problema;
    }
}
