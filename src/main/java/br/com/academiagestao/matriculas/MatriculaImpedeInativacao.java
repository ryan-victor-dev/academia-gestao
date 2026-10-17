package br.com.academiagestao.matriculas;

import br.com.academiagestao.alunos.ImpedimentoParaInativar;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Aluno com matrícula vigente ou agendada não é inativado: cancele antes (ADR 0016).
 * Cancelar envolve dinheiro e motivo, e não deve acontecer como efeito colateral.
 */
@Component
class MatriculaImpedeInativacao implements ImpedimentoParaInativar {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MatriculaRepository repository;
    private final Clock relogio;

    MatriculaImpedeInativacao(MatriculaRepository repository) {
        this.repository = repository;
        this.relogio = Clock.systemUTC();
    }

    @Override
    public Optional<String> verificar(UUID alunoId) {
        LocalDate hoje = Matricula.hoje(relogio.instant());
        // Vigente, trancada ou agendada: ainda não terminou e não foi cancelada nem trocada.
        return repository.pendentes(alunoId, hoje).stream().findFirst()
                .map(m -> "O aluno tem matrícula de %s a %s; cancele-a antes de inativar".formatted(
                        m.getInicio().format(DATA), m.getFim().format(DATA)));
    }
}
