package br.com.academiagestao.planos;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.RecursoModificadoException;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.PLANO;

@Service
class PlanoService {

    // Nenhum plano tem este id: usado quando não há plano a ignorar na checagem de nome.
    private static final UUID NENHUM = new UUID(0, 0);

    private final PlanoRepository repository;
    private final Auditoria auditoria;

    PlanoService(PlanoRepository repository, Auditoria auditoria) {
        this.repository = repository;
        this.auditoria = auditoria;
    }

    @Transactional
    Plano cadastrar(DadosPlano dados) {
        Plano plano = Plano.novo(dados);
        garantirNomeLivre(plano.getNome(), NENHUM);
        return gravar(plano, "CRIADO", Map.of());
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
        Map<String, Object> antes = retrato(plano);
        plano.atualizar(dados);
        return gravar(plano, "EDITADO", antes);
    }

    @Transactional
    Plano inativar(UUID id) {
        Plano plano = buscar(id);
        Map<String, Object> antes = retrato(plano);
        plano.inativar();
        return gravar(plano, "INATIVADO", antes);
    }

    /** Volta a ser oferecido, desde que nenhum outro plano ativo tenha tomado o nome. */
    @Transactional
    Plano reativar(UUID id) {
        Plano plano = buscar(id);
        Map<String, Object> antes = retrato(plano);
        if (!plano.isAtivo()) {
            garantirNomeLivre(plano.getNome(), id);
            plano.reativar();
        }
        return gravar(plano, "REATIVADO", antes);
    }

    private void garantirNomeLivre(String nome, UUID ignorar) {
        if (repository.existeOutroAtivoComNome(nome, ignorar)) {
            throw new NomeDePlanoEmUsoException();
        }
    }

    // A checagem acima dá a mensagem clara; numa corrida, quem barra é o índice único do
    // banco (nome) ou a versão (edição simultânea), e os dois viram a mesma resposta.
    // A trilha é gravada depois, na mesma transação: só fica se a alteração ficar.
    private Plano gravar(Plano plano, String acao, Map<String, Object> antes) {
        Plano gravado;
        try {
            gravado = repository.saveAndFlush(plano);
        } catch (DataIntegrityViolationException e) {
            throw new NomeDePlanoEmUsoException();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RecursoModificadoException("o plano");
        }
        auditoria.registrar(PLANO, gravado.getId(), acao, antes, retrato(gravado));
        return gravado;
    }

    // Tudo o que muda o que o aluno paga ou as regras de saída (preço, fidelidade, multa).
    private static Map<String, Object> retrato(Plano plano) {
        return Auditoria.campos("nome", plano.getNome(), "valorMensal", plano.getValorMensal(),
                "duracaoMeses", plano.getDuracaoMeses(), "fidelidade", plano.isFidelidade(),
                "percentualMulta", plano.getPercentualMulta(), "formaPagamento", plano.getFormaPagamento(),
                "ativo", plano.isAtivo());
    }
}
