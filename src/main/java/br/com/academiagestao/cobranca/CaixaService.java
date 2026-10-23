package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.LancamentoRepository.DinheiroDoCaixa;
import br.com.academiagestao.cobranca.dominio.Caixa;
import br.com.academiagestao.cobranca.dominio.Caixa.Fechamento;
import br.com.academiagestao.cobranca.dominio.Caixa.Reabertura;
import br.com.academiagestao.cobranca.dominio.OperacaoDeCaixaRecusadaException;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Calendario;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.CAIXA;

/**
 * Abrir, fechar e reabrir o caixa do dia (ADR 0031). Cada operação vai para a trilha na mesma
 * transação. As travas contra a corrida com os pagamentos estão no {@link CaixaRepository}.
 */
@Service
class CaixaService {

    private final CaixaRepository repository;
    private final LancamentoRepository lancamentos;
    private final Auditoria auditoria;
    private final Clock relogio;

    CaixaService(CaixaRepository repository, LancamentoRepository lancamentos, Auditoria auditoria, Clock relogio) {
        this.repository = repository;
        this.lancamentos = lancamentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /** O caixa com o histórico de fechamentos e reaberturas. */
    record Consulta(Caixa caixa, List<Fechamento> fechamentos, List<Reabertura> reaberturas) {
    }

    /**
     * Abre o caixa de hoje com o troco. Recusa se ficou um dia anterior aberto (esquecido ou
     * reaberto): nenhum dia fica sem fechamento.
     */
    @Transactional
    Consulta abrir(BigDecimal troco, UUID usuarioId) {
        Instant agora = relogio.instant();
        LocalDate hoje = Calendario.hoje(agora);
        Caixa caixa = Caixa.abrir(hoje, troco, usuarioId, agora);
        repository.anteriorAberto(hoje).ifPresent(dia -> {
            throw new OperacaoDeCaixaRecusadaException("O caixa de " + dia
                    + " ainda está aberto: feche-o (com a contagem) antes de abrir o de hoje");
        });
        try {
            repository.inserir(caixa);
        } catch (DuplicateKeyException e) {
            // Um caixa por dia (V35): já aberto antes, ou outra abertura ganhou a corrida.
            throw new OperacaoDeCaixaRecusadaException("O caixa de " + hoje
                    + " já foi aberto; se foi fechado, só o ADMIN reabre");
        }
        auditoria.registrar(CAIXA, caixa.getId(), "ABERTO", Auditoria.campos(),
                Auditoria.campos("dia", hoje.toString(), "troco", caixa.getTroco()));
        return consulta(caixa);
    }

    /**
     * Fecha com o dinheiro contado. Trava o caixa antes de somar: um pagamento em andamento
     * termina antes e entra na conta; um que chega depois encontra o caixa fechado.
     */
    @Transactional
    Consulta fechar(LocalDate dia, BigDecimal contado, String justificativa, UUID usuarioId) {
        Caixa caixa = travar(dia);
        DinheiroDoCaixa dinheiro = lancamentos.dinheiroDoCaixa(caixa.getId());
        Fechamento fechamento = caixa.fechar(contado, dinheiro.recebido(), dinheiro.estornado(), justificativa,
                usuarioId, relogio.instant());
        repository.atualizarSituacao(caixa);
        repository.inserir(fechamento);
        auditoria.registrar(CAIXA, caixa.getId(), "FECHADO", Auditoria.campos("situacao", "ABERTO"),
                Auditoria.campos("situacao", "FECHADO", "dia", dia.toString(), "esperado", fechamento.esperado(),
                        "contado", fechamento.contado(), "diferenca", fechamento.diferenca(),
                        "justificativa", fechamento.justificativa()));
        return consulta(caixa);
    }

    /** Só o ADMIN, com motivo. O fechamento anterior fica no histórico. */
    @Transactional
    Consulta reabrir(LocalDate dia, String motivo, UUID usuarioId) {
        Caixa caixa = travar(dia);
        Reabertura reabertura = caixa.reabrir(motivo, usuarioId, relogio.instant());
        repository.atualizarSituacao(caixa);
        repository.inserir(reabertura);
        auditoria.registrar(CAIXA, caixa.getId(), "REABERTO", Auditoria.campos("situacao", "FECHADO"),
                Auditoria.campos("situacao", "ABERTO", "dia", dia.toString(), "motivo", reabertura.motivo()));
        return consulta(caixa);
    }

    @Transactional(readOnly = true)
    Consulta doDia(LocalDate dia) {
        return consulta(repository.doDia(dia).orElseThrow(() -> new CaixaNaoEncontradoException(dia)));
    }

    private Caixa travar(LocalDate dia) {
        return repository.travar(dia).orElseThrow(() -> new CaixaNaoEncontradoException(dia));
    }

    private Consulta consulta(Caixa caixa) {
        return new Consulta(caixa, repository.fechamentos(caixa.getId()), repository.reaberturas(caixa.getId()));
    }
}
