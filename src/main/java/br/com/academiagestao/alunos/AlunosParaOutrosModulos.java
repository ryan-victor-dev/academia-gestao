package br.com.academiagestao.alunos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O que os outros módulos (hoje matrículas, treinos, cobrança e pagamentos) podem pedir ao de
 * alunos. Devolve dados, não lança as exceções daqui: quem decide a resposta HTTP é o módulo
 * que chamou.
 */
@Service
public class AlunosParaOutrosModulos {

    private final AlunoRepository repository;

    AlunosParaOutrosModulos(AlunoRepository repository) {
        this.repository = repository;
    }

    /**
     * Trava a linha do aluno até o fim da transação de quem chamou (SELECT ... FOR UPDATE)
     * e diz se ele existe e está ativo. Com a trava, matricular (ou montar uma ficha) e
     * inativar o mesmo aluno ao mesmo tempo não passam juntos pelas checagens: um espera o
     * outro (ADR 0016).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EstadoDoAluno travar(UUID alunoId) {
        return repository.travarPorId(alunoId)
                .map(aluno -> aluno.isAtivo() ? EstadoDoAluno.ATIVO : EstadoDoAluno.INATIVO)
                .orElse(EstadoDoAluno.INEXISTENTE);
    }

    /**
     * Nome de cada aluno ativo entre {@code ids}; inativos e inexistentes ficam de fora. Para
     * listas da equipe (as revisões vencidas dos treinos), que mostram quem é o aluno. Só o id
     * e o nome, sem carregar o cadastro (CPF, telefone) de cada um.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> nomesDosAtivos(Collection<UUID> ids) {
        return repository.nomesDosAtivos(ids).stream()
                .collect(Collectors.toMap(AlunoRepository.NomeDoAluno::id, AlunoRepository.NomeDoAluno::nome));
    }

    /**
     * Nome de cada aluno entre {@code ids}, ativo ou não (inexistentes ficam de fora). Para a lista
     * de cobranças atrasadas da recepção: a dívida continua depois de o aluno ser inativado.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> nomes(Collection<UUID> ids) {
        return repository.nomes(ids).stream()
                .collect(Collectors.toMap(AlunoRepository.NomeDoAluno::id, AlunoRepository.NomeDoAluno::nome));
    }

    /** Quem paga um boleto (ADR 0034): a empresa de pagamentos exige o nome e o CPF. */
    public record Pagador(String nome, String cpf, String email) {
    }

    /** Os dados de quem paga, para o pagamento online. Inexistente: vazio. */
    @Transactional(readOnly = true)
    public Optional<Pagador> pagador(UUID alunoId) {
        return repository.findById(alunoId).map(a -> new Pagador(a.getNome(), a.getCpf(), a.getEmail()));
    }
}
