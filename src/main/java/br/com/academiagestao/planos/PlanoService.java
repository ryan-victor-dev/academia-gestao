package br.com.academiagestao.planos;

import br.com.academiagestao.compartilhado.RecursoModificadoException;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class PlanoService {

    // Nenhum plano tem este id: usado quando não há plano a ignorar na checagem de nome.
    private static final UUID NENHUM = new UUID(0, 0);

    private final PlanoRepository repository;

    PlanoService(PlanoRepository repository) {
        this.repository = repository;
    }

    @Transactional
    Plano cadastrar(DadosPlano dados) {
        Plano plano = Plano.novo(dados);
        garantirNomeLivre(plano.getNome(), NENHUM);
        return gravar(plano);
    }

    @Transactional(readOnly = true)
    Plano buscar(UUID id) {
        return repository.findById(id).orElseThrow(() -> new PlanoNaoEncontradoException(id));
    }

    @Transactional(readOnly = true)
    List<Plano> listar(boolean incluirInativos) {
        return incluirInativos ? repository.findAllByOrderByNomeAsc() : repository.findByAtivoTrueOrderByNomeAsc();
    }

    /** Só altera se o If-Match bater com a versão atual do plano. */
    @Transactional
    Plano atualizar(UUID id, DadosPlano dados, String ifMatch) {
        Plano plano = buscar(id);
        VersaoEsperada.conferir(ifMatch, plano.getVersao(), "o plano");
        // O nome é conferido ANTES de alterar o plano: a consulta é SQL nativo, e antes dela o
        // Hibernate grava as alterações pendentes. Com o plano já alterado, o UPDATE esbarraria
        // no índice único dentro da própria checagem, e o cliente receberia um 500.
        if (plano.isAtivo()) {
            garantirNomeLivre(Plano.limparNome(dados.nome()), id);
        }
        plano.atualizar(dados);
        return gravar(plano);
    }

    @Transactional
    Plano inativar(UUID id) {
        Plano plano = buscar(id);
        plano.inativar();
        return gravar(plano);
    }

    /** Volta a ser oferecido, desde que nenhum outro plano ativo tenha tomado o nome. */
    @Transactional
    Plano reativar(UUID id) {
        Plano plano = buscar(id);
        if (!plano.isAtivo()) {
            garantirNomeLivre(plano.getNome(), id);
            plano.reativar();
        }
        return gravar(plano);
    }

    private void garantirNomeLivre(String nome, UUID ignorar) {
        if (repository.existeOutroAtivoComNome(nome, ignorar)) {
            throw new NomeDePlanoEmUsoException();
        }
    }

    // A checagem acima dá a mensagem clara; numa corrida, quem barra é o índice único do
    // banco (nome) ou a versão (edição simultânea), e os dois viram a mesma resposta.
    private Plano gravar(Plano plano) {
        try {
            return repository.saveAndFlush(plano);
        } catch (DataIntegrityViolationException e) {
            throw new NomeDePlanoEmUsoException();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RecursoModificadoException("o plano");
        }
    }
}
