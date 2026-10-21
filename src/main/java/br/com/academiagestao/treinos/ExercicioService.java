package br.com.academiagestao.treinos;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.RecursoModificadoException;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.EXERCICIO;

@Service
class ExercicioService {

    // Nenhum exercício tem este id: usado quando não há exercício a ignorar na checagem de nome.
    private static final UUID NENHUM = new UUID(0, 0);

    private final ExercicioRepository repository;
    private final Auditoria auditoria;
    private final Clock relogio;

    ExercicioService(ExercicioRepository repository, Auditoria auditoria, Clock relogio) {
        this.repository = repository;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @Transactional
    Exercicio cadastrar(DadosExercicio dados) {
        Exercicio exercicio = Exercicio.novo(dados, relogio.instant());
        garantirNomeLivre(exercicio.getNome(), NENHUM);
        return gravar(exercicio, "CRIADO", Map.of());
    }

    @Transactional(readOnly = true)
    Exercicio buscar(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ExercicioNaoEncontradoException(id));
    }

    @Transactional(readOnly = true)
    List<Exercicio> listar(GrupoMuscular grupo, boolean incluirInativos) {
        return repository.listar(grupo, incluirInativos);
    }

    /** Só altera se o If-Match bater com a versão atual do exercício. */
    @Transactional
    Exercicio atualizar(UUID id, DadosExercicio dados, String ifMatch) {
        Exercicio exercicio = buscar(id);
        VersaoEsperada.conferir(ifMatch, exercicio.getVersao(), "o exercício");
        // Conferido ANTES de alterar: a consulta nativa grava antes as alterações pendentes, e
        // o índice único estouraria dentro da própria checagem (500 em vez de 409).
        if (exercicio.isAtivo()) {
            garantirNomeLivre(Exercicio.limpar(dados.nome()), id);
        }
        Map<String, Object> antes = retrato(exercicio);
        exercicio.atualizar(dados);
        return gravar(exercicio, "EDITADO", antes);
    }

    @Transactional
    Exercicio inativar(UUID id) {
        Exercicio exercicio = buscar(id);
        Map<String, Object> antes = retrato(exercicio);
        exercicio.inativar();
        return gravar(exercicio, "INATIVADO", antes);
    }

    /** Volta ao catálogo, desde que nenhum outro exercício ativo tenha tomado o nome. */
    @Transactional
    Exercicio reativar(UUID id) {
        Exercicio exercicio = buscar(id);
        Map<String, Object> antes = retrato(exercicio);
        if (!exercicio.isAtivo()) {
            garantirNomeLivre(exercicio.getNome(), id);
            exercicio.reativar();
        }
        return gravar(exercicio, "REATIVADO", antes);
    }

    private void garantirNomeLivre(String nome, UUID ignorar) {
        if (repository.existeOutroAtivoComNome(nome, ignorar)) {
            throw new NomeDeExercicioEmUsoException();
        }
    }

    // A checagem acima dá a mensagem clara; numa corrida, quem barra é o índice único do
    // banco (nome) ou a versão (edição simultânea). A trilha vai na mesma transação.
    private Exercicio gravar(Exercicio exercicio, String acao, Map<String, Object> antes) {
        Exercicio gravado;
        try {
            gravado = repository.saveAndFlush(exercicio);
        } catch (DataIntegrityViolationException e) {
            throw new NomeDeExercicioEmUsoException();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RecursoModificadoException("o exercício");
        }
        auditoria.registrar(EXERCICIO, gravado.getId(), acao, antes, retrato(gravado));
        return gravado;
    }

    private static Map<String, Object> retrato(Exercicio exercicio) {
        return Auditoria.campos("nome", exercicio.getNome(), "grupoMuscular", exercicio.getGrupoMuscular(),
                "observacao", exercicio.getObservacao(), "ativo", exercicio.isAtivo());
    }
}
