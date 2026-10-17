package br.com.academiagestao.matriculas;

class MatriculaNaoCancelavelException extends RuntimeException {

    MatriculaNaoCancelavelException(SituacaoMatricula situacao) {
        super("Só se cancela matrícula agendada ou vigente; esta está " + situacao.name().toLowerCase());
    }
}
