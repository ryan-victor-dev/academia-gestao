package br.com.academiagestao.matriculas;

import br.com.academiagestao.alunos.AlunosParaMatricula;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import br.com.academiagestao.planos.PlanoOfertado;
import br.com.academiagestao.planos.PlanosParaMatricula;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
class MatriculaService {

    private static final String RESTRICAO_DE_PERIODO = "ex_matriculas_periodo_sobreposto";
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MatriculaRepository repository;
    private final AlunosParaMatricula alunos;
    private final PlanosParaMatricula planos;
    private final Clock relogio;

    MatriculaService(MatriculaRepository repository, AlunosParaMatricula alunos, PlanosParaMatricula planos,
                     Clock relogio) {
        this.repository = repository;
        this.alunos = alunos;
        this.planos = planos;
        this.relogio = relogio;
    }

    /**
     * Copia o valor e a duração do plano de hoje (ADR 0013). A linha do aluno fica travada
     * até o fim da transação: duas matrículas do mesmo aluno ao mesmo tempo (ou matricular
     * enquanto alguém o inativa) passam pelas checagens uma de cada vez.
     */
    @Transactional
    Matricula matricular(UUID alunoId, UUID planoId, LocalDate inicio) {
        switch (alunos.travar(alunoId)) {
            case INEXISTENTE -> throw new DadosInvalidosException("aluno " + alunoId + " não encontrado");
            case INATIVO -> throw new MatriculaRecusadaException("Aluno inativo",
                    "O aluno está inativo; reative-o antes de matricular");
            case ATIVO -> { }
        }
        PlanoOfertado plano = planos.buscar(planoId)
                .orElseThrow(() -> new DadosInvalidosException("plano " + planoId + " não encontrado"));
        if (!plano.ativo()) {
            throw new MatriculaRecusadaException("Plano inativo", "O plano não está mais sendo oferecido");
        }

        PlanoContratado contratado = new PlanoContratado(plano.id(), plano.nome(), plano.valorMensal(),
                plano.duracaoMeses(), plano.fidelidade(), plano.percentualMulta(), plano.formaPagamento());
        Matricula matricula = Matricula.nova(alunoId, contratado, inicio, relogio.instant());
        List<Matricula> sobrepostas = repository.sobrepostas(alunoId, matricula.getInicio(), matricula.getFim());
        if (!sobrepostas.isEmpty()) {
            throw periodoOcupado(sobrepostas.getLast());
        }
        try {
            return repository.saveAndFlush(matricula);
        } catch (DataIntegrityViolationException e) {
            // A trava do aluno já evita a corrida; a restrição do banco é a última garantia
            // (e vale para INSERT feito fora da aplicação). Outra violação é bug: 500.
            if (String.valueOf(e.getMessage()).contains(RESTRICAO_DE_PERIODO)) {
                throw new MatriculaRecusadaException("Período ocupado",
                        "O aluno já tem matrícula neste período");
            }
            throw e;
        }
    }

    // A mensagem diz até quando vai a matrícula que conflita e de quando a nova pode começar.
    private static MatriculaRecusadaException periodoOcupado(Matricula existente) {
        return new MatriculaRecusadaException("Período ocupado",
                "O aluno já tem matrícula de %s a %s; a nova pode começar a partir de %s".formatted(
                        existente.getInicio().format(DATA), existente.getFim().format(DATA),
                        existente.getFim().plusDays(1).format(DATA)));
    }

    @Transactional(readOnly = true)
    Matricula buscar(UUID id) {
        return repository.findById(id).orElseThrow(() -> new MatriculaNaoEncontradaException(id));
    }

    @Transactional(readOnly = true)
    List<Matricula> listarDoAluno(UUID alunoId) {
        return repository.findByAlunoIdOrderByInicioDesc(alunoId);
    }

    /** Hoje cai numa matrícula vigente (não cancelada nem trancada)? É o que o instrutor precisa saber. */
    @Transactional(readOnly = true)
    boolean temMatriculaVigente(UUID alunoId) {
        Instant agora = relogio.instant();
        LocalDate hoje = Matricula.hoje(agora);
        return repository.sobrepostas(alunoId, hoje, hoje).stream()
                .anyMatch(m -> m.situacao(agora) == SituacaoMatricula.VIGENTE);
    }

    /**
     * Tranca e estende o fim (ADR 0018). Trava o aluno antes, como a matrícula nova: assim uma
     * renovação criada ao mesmo tempo espera, e a conferência abaixo vê o que existe de fato.
     */
    @Transactional
    Matricula trancar(UUID id, LocalDate inicio, LocalDate voltaPrevista, String motivo, UUID usuarioId) {
        UUID alunoId = repository.alunoDaMatricula(id).orElseThrow(() -> new MatriculaNaoEncontradaException(id));
        alunos.travar(alunoId);
        Matricula matricula = repository.travarPorId(id).orElseThrow(() -> new MatriculaNaoEncontradaException(id));
        // Consultada ANTES de alterar a matrícula: com o fim já estendido, o auto-flush do
        // Hibernate gravaria a alteração antes da consulta e a restrição do banco estouraria nela.
        var renovacao = repository.findFirstByAlunoIdAndCanceladaEmIsNullAndInicioAfterOrderByInicio(
                alunoId, matricula.getFim());

        matricula.trancar(inicio, voltaPrevista, motivo, usuarioId, relogio.instant());
        if (renovacao.isPresent() && !renovacao.get().getInicio().isAfter(matricula.getFim())) {
            throw new MatriculaRecusadaException("Trancamento recusado",
                    "O aluno tem renovação agendada a partir de %s, e o fim estendido a invadiria; cancele-a antes"
                            .formatted(renovacao.get().getInicio().format(DATA)));
        }
        try {
            return repository.saveAndFlush(matricula);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains(RESTRICAO_DE_PERIODO)) {
                throw new MatriculaRecusadaException("Trancamento recusado",
                        "O fim estendido invadiria outra matrícula do aluno");
            }
            throw e;
        }
    }

    /**
     * Troca imediata de plano (ADR 0018): a atual termina na véspera e a nova começa hoje, com
     * nome, preço e regras do novo plano. Trava o aluno, como matricular e trancar.
     */
    @Transactional
    Matricula trocarPlano(UUID id, UUID novoPlanoId, UUID usuarioId) {
        UUID alunoId = repository.alunoDaMatricula(id).orElseThrow(() -> new MatriculaNaoEncontradaException(id));
        alunos.travar(alunoId);
        Matricula atual = repository.travarPorId(id).orElseThrow(() -> new MatriculaNaoEncontradaException(id));
        PlanoOfertado plano = planos.buscar(novoPlanoId)
                .orElseThrow(() -> new DadosInvalidosException("plano " + novoPlanoId + " não encontrado"));
        if (!plano.ativo()) {
            throw new MatriculaRecusadaException("Plano inativo", "O plano não está mais sendo oferecido");
        }
        // Consultada antes de alterar a atual (auto-flush, ver trancar).
        var renovacao = repository.findFirstByAlunoIdAndCanceladaEmIsNullAndInicioAfterOrderByInicio(
                alunoId, atual.getFim());
        if (renovacao.isPresent()) {
            throw new MatriculaRecusadaException("Troca recusada",
                    "O aluno tem renovação agendada a partir de %s; cancele-a antes de trocar de plano"
                            .formatted(renovacao.get().getInicio().format(DATA)));
        }

        Instant agora = relogio.instant();
        PlanoContratado contratado = new PlanoContratado(plano.id(), plano.nome(), plano.valorMensal(),
                plano.duracaoMeses(), plano.fidelidade(), plano.percentualMulta(), plano.formaPagamento());
        Matricula nova = Matricula.nova(alunoId, contratado, null, agora);
        atual.encerrarPorTroca(nova, usuarioId, agora);
        // A atual é gravada primeiro: encurtada, ela libera o período para a nova, que começa hoje.
        repository.saveAndFlush(atual);
        return repository.saveAndFlush(nova);
    }

    /** O aluno voltou antes do previsto (ou desistiu do trancamento agendado). */
    @Transactional
    Matricula registrarVolta(UUID id, UUID usuarioId) {
        Matricula matricula = repository.travarPorId(id).orElseThrow(() -> new MatriculaNaoEncontradaException(id));
        matricula.registrarVolta(usuarioId, relogio.instant());
        return repository.saveAndFlush(matricula);
    }

    /** Trava a matrícula: um segundo cancelamento simultâneo espera e recebe 409 (já cancelada). */
    @Transactional
    Matricula cancelar(UUID id, String motivo, UUID usuarioId) {
        Matricula matricula = repository.travarPorId(id).orElseThrow(() -> new MatriculaNaoEncontradaException(id));
        matricula.cancelar(usuarioId, motivo, relogio.instant());
        return repository.saveAndFlush(matricula);
    }

    /** O mesmo relógio do service, para a resposta calcular a situação de cada matrícula. */
    Instant agora() {
        return relogio.instant();
    }
}
