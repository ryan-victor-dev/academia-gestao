package br.com.academiagestao.pagamentos;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Devolucao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.PAGAMENTO_ONLINE;

/**
 * O dinheiro que chegou sem ter o que pagar (ADRs 0035 e 0036): a lista do ADMIN, a devolução pela
 * empresa e a marcação do que ele devolveu por fora (diagnóstico de 27/10, B6).
 */
@Service
class DevolucaoDePagamentoOnlineService {

    private final EmpresaDePagamento empresa;
    private final PagamentoOnlineRepository repository;
    private final Auditoria auditoria;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    DevolucaoDePagamentoOnlineService(EmpresaDePagamento empresa, PagamentoOnlineRepository repository,
                                      Auditoria auditoria, PlatformTransactionManager transacoes, Clock relogio) {
        this.empresa = empresa;
        this.repository = repository;
        this.auditoria = auditoria;
        this.transacao = new TransactionTemplate(transacoes);
        this.relogio = relogio;
    }

    /** Só o ADMIN: o dinheiro que chegou sem ter o que pagar. */
    List<PagamentoOnline> aDevolver() {
        return repository.aDevolver();
    }

    /**
     * O ADMIN confirma, e o sistema pede a devolução à empresa (decisão do dono, ADR 0035). A
     * chamada fica fora de transação; a chave da devolução é o id do pagamento, então repetir
     * (clique duplo, resposta perdida na rede, DEVOLVENDO parado) não devolve duas vezes. Antes
     * da chamada, o pagamento vira DEVOLVENDO, e "por fora" é recusado (ADR 0036).
     */
    PagamentoOnline devolverPelaEmpresa(UUID id, UUID usuarioId) {
        ProvedorDePagamento empresa = this.empresa.ligada();
        PagamentoOnline pagamento = repository.buscar(id)
                .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Pagamento online não encontrado"));
        if (pagamento.getSituacao() != PagamentoOnline.Situacao.A_DEVOLVER
                && pagamento.getSituacao() != PagamentoOnline.Situacao.DEVOLVENDO) {
            throw new PagamentoOnlineRecusadoException("Só o que está a devolver pode ser devolvido");
        }
        if (!pagamento.getProvedor().equals(empresa.nome())) {
            throw new PagamentoOnlineRecusadoException("Recebido pela empresa " + pagamento.getProvedor()
                    + ": devolva pelo painel dela e marque como devolvido");
        }
        if (!empresa.devolve(pagamento.getForma())) {
            throw new PagamentoOnlineRecusadoException("A empresa de pagamentos não devolve "
                    + pagamento.getForma().nome() + " pelo sistema: devolva por transferência e marque como devolvido");
        }
        transacao.executeWithoutResult(t -> {
            PagamentoOnline travado = repository.travar(id).orElseThrow();
            if (travado.getSituacao() == PagamentoOnline.Situacao.A_DEVOLVER) {
                travado.iniciarDevolucao();
                repository.atualizar(travado);
            } else if (travado.getSituacao() != PagamentoOnline.Situacao.DEVOLVENDO) {
                throw new PagamentoOnlineRecusadoException("Só o que está a devolver pode ser devolvido");
            }
        });
        try {
            empresa.devolver(new Devolucao(pagamento.getIdExterno(), pagamento.getValorADevolver(), pagamento.getId()));
        } catch (RuntimeException e) {
            // A empresa recusou ou não respondeu: volta a ficar a devolver (de novo, ou por fora).
            transacao.executeWithoutResult(t -> repository.travar(id)
                    .filter(p -> p.getSituacao() == PagamentoOnline.Situacao.DEVOLVENDO)
                    .ifPresent(p -> {
                        p.desistirDaDevolucao();
                        repository.atualizar(p);
                    }));
            throw e;
        }
        return transacao.execute(t -> {
            PagamentoOnline travado = repository.travar(id).orElseThrow();
            // Já devolvido: o outro pedido (mesma chave na empresa) chegou antes.
            if (travado.getSituacao() == PagamentoOnline.Situacao.DEVOLVENDO
                    || travado.getSituacao() == PagamentoOnline.Situacao.A_DEVOLVER) {
                registrarDevolvido(travado, usuarioId, "PELA_EMPRESA");
            }
            return travado;
        });
    }

    /** O ADMIN devolveu por fora (transferência, painel da empresa) e marca aqui. */
    PagamentoOnline marcarDevolvido(UUID id, UUID usuarioId) {
        return transacao.execute(t -> {
            PagamentoOnline pagamento = repository.travar(id)
                    .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Pagamento online não encontrado"));
            if (pagamento.getSituacao() == PagamentoOnline.Situacao.DEVOLVENDO) {
                throw new PagamentoOnlineRecusadoException("A devolução pela empresa está em andamento: espere ou "
                        + "tente devolver de novo pelo sistema");
            }
            if (pagamento.getSituacao() != PagamentoOnline.Situacao.A_DEVOLVER) {
                throw new PagamentoOnlineRecusadoException("Só o que está a devolver pode ser marcado como devolvido");
            }
            registrarDevolvido(pagamento, usuarioId, "POR_FORA");
            return pagamento;
        });
    }

    private void registrarDevolvido(PagamentoOnline pagamento, UUID usuarioId, String como) {
        Map<String, Object> antes = Auditoria.campos("situacao", pagamento.getSituacao().name());
        pagamento.devolver(usuarioId, relogio.instant());
        repository.atualizar(pagamento);
        auditoria.registrar(PAGAMENTO_ONLINE, pagamento.getId(), "DEVOLVIDO", antes,
                Auditoria.campos("situacao", "DEVOLVIDO", "recebido", pagamento.getRecebido(),
                        "devolvido", pagamento.getValorADevolver(), "como", como));
    }

    /** Se a empresa ligada devolve, pelo sistema, este pagamento (para a tela do ADMIN). */
    boolean devolvePeloSistema(PagamentoOnline pagamento) {
        return empresa.talvez().filter(p -> p.nome().equals(pagamento.getProvedor()) && p.devolve(pagamento.getForma()))
                .isPresent();
    }
}
