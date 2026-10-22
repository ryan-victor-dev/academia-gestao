package br.com.academiagestao.treinos;

import br.com.academiagestao.alunos.AlunosParaMatricula;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import br.com.academiagestao.compartilhado.Pagina;
import br.com.academiagestao.compartilhado.RecursoModificadoException;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.FICHA;

@Service
class FichaService {

    private final FichaRepository repository;
    private final ExercicioRepository exercicios;
    private final AlunosParaMatricula alunos;
    private final Auditoria auditoria;
    private final Clock relogio;

    FichaService(FichaRepository repository, ExercicioRepository exercicios, AlunosParaMatricula alunos,
                 Auditoria auditoria, Clock relogio) {
        this.repository = repository;
        this.exercicios = exercicios;
        this.alunos = alunos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * Monta a ficha nova do aluno e encerra a anterior, na mesma transação. A linha do aluno
     * fica travada: duas fichas ao mesmo tempo para o mesmo aluno passam uma de cada vez, e
     * uma inativação ao mesmo tempo também espera.
     */
    @Transactional
    Ficha montar(UUID alunoId, DadosFicha dados, UUID montadaPor) {
        switch (alunos.travar(alunoId)) {
            case INEXISTENTE -> throw new DadosInvalidosException("aluno " + alunoId + " não encontrado");
            case INATIVO -> throw new FichaRecusadaException("Aluno inativo",
                    "O aluno está inativo; reative-o antes de montar uma ficha nova");
            case ATIVO -> { }
        }
        Instant agora = relogio.instant();
        // Conferida antes de encerrar a anterior: ficha inválida não encerra nada.
        Ficha nova = Ficha.montar(alunoId, dados, nomesDosExercicios(dados, Set.of()), montadaPor, agora);

        repository.buscarAtual(alunoId).ifPresent(anterior -> {
            Map<String, Object> antes = anterior.retrato();
            anterior.encerrar(agora);
            // Gravada antes da nova: o banco admite uma só atual por aluno.
            repository.saveAndFlush(anterior);
            auditoria.registrar(FICHA, anterior.getId(), "ENCERRADA", antes, anterior.retrato());
        });
        Ficha gravada = repository.saveAndFlush(nova);
        auditoria.registrar(FICHA, gravada.getId(), "MONTADA", Map.of(), gravada.retrato());
        return gravada;
    }

    /** Troca as divisões e a revisão da ficha atual. Só se o If-Match bater com a versão dela. */
    @Transactional
    Ficha corrigir(UUID id, DadosFicha dados, String ifMatch) {
        UUID alunoId = repository.alunoDaFicha(id).orElseThrow(() -> FichaNaoEncontradaException.comId(id));
        // A mesma trava de montar: uma ficha nova (que encerra esta) e a correção passam uma de
        // cada vez, e a correção vê a ficha já encerrada (409), em vez de um conflito de versão.
        alunos.travar(alunoId);
        Ficha ficha = buscar(id);
        ficha.garantirAtual();
        VersaoEsperada.conferir(ifMatch, ficha.getVersao(), "a ficha");
        // Os que já estão nesta ficha podem ficar, mesmo inativados depois: corrigir a carga
        // não obriga a trocar o exercício.
        Set<UUID> jaNaFicha = ficha.getDivisoes().stream()
                .flatMap(d -> d.getExercicios().stream())
                .map(ExercicioDaFicha::getExercicioId)
                .collect(Collectors.toSet());
        Map<UUID, String> nomes = nomesDosExercicios(dados, jaNaFicha);

        Map<String, Object> antes = ficha.retrato();
        ficha.corrigir(dados, nomes, relogio.instant());
        Ficha gravada;
        try {
            gravada = repository.saveAndFlush(ficha);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RecursoModificadoException("a ficha");
        }
        auditoria.registrar(FICHA, gravada.getId(), "CORRIGIDA", antes, gravada.retrato());
        return gravada;
    }

    @Transactional(readOnly = true)
    Ficha buscar(UUID id) {
        return repository.findById(id).orElseThrow(() -> FichaNaoEncontradaException.comId(id));
    }

    @Transactional(readOnly = true)
    Ficha atual(UUID alunoId) {
        return repository.buscarAtual(alunoId).orElseThrow(() -> FichaNaoEncontradaException.semAtual(alunoId));
    }

    /** A atual e as encerradas, da mais nova para a mais antiga, uma página por vez. */
    @Transactional(readOnly = true)
    Page<Ficha> historico(UUID alunoId, int pagina, int tamanho) {
        return repository.historico(alunoId, PageRequest.of(pagina, tamanho));
    }

    /**
     * As fichas atuais com a revisão vencida (até hoje), com o nome do aluno, uma página por
     * vez. Aluno inativo fica de fora: não está treinando, e a ficha dele não precisa de
     * revisão agora. Quem foi inativado entre as duas consultas sai também (a página fica
     * com um a menos, uma vez).
     */
    @Transactional(readOnly = true)
    Pagina<RevisaoPendente> revisoesVencidas(int pagina, int tamanho) {
        Page<FichaRepository.RevisaoVencida> vencidas = repository.revisoesVencidas(hoje(),
                PageRequest.of(pagina, tamanho));
        Map<UUID, String> nomes = alunos.nomesDosAtivos(
                vencidas.stream().map(FichaRepository.RevisaoVencida::alunoId).collect(Collectors.toSet()));
        List<RevisaoPendente> conteudo = vencidas.stream()
                .filter(v -> nomes.containsKey(v.alunoId()))
                .map(v -> new RevisaoPendente(v, nomes.get(v.alunoId())))
                .toList();
        return new Pagina<>(conteudo, vencidas.getNumber(), vencidas.getSize(), vencidas.getTotalElements(),
                vencidas.getTotalPages());
    }

    record RevisaoPendente(FichaRepository.RevisaoVencida ficha, String alunoNome) {
    }

    LocalDate hoje() {
        return Calendario.hoje(relogio.instant());
    }

    /**
     * Nome atual no catálogo de cada exercício citado. Inativo só entra se estiver em
     * {@code permitidosInativos}; o que não existe fica de fora, e a Ficha o recusa (400).
     */
    private Map<UUID, String> nomesDosExercicios(DadosFicha dados, Set<UUID> permitidosInativos) {
        Map<UUID, String> nomes = new HashMap<>();
        if (dados == null) {
            return nomes;
        }
        for (Exercicio exercicio : exercicios.findAllById(dados.exercicioIds())) {
            if (!exercicio.isAtivo() && !permitidosInativos.contains(exercicio.getId())) {
                throw new FichaRecusadaException("Exercício inativo", "O exercício \"" + exercicio.getNome()
                        + "\" está inativo e não entra em fichas novas; reative-o no catálogo ou escolha outro");
            }
            nomes.put(exercicio.getId(), exercicio.getNome());
        }
        return nomes;
    }
}
