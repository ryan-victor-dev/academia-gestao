package br.com.academiagestao.pagamentos;

import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.FormaOnline;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.ResultadoDoRecebimento;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.ValorCombinado;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Estado;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Situacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.PAGAMENTO_ONLINE;

/**
 * O que a empresa diz de cada pagamento online (ADRs 0034 a 0037): o aviso dela, as consultas
 * periódicas (em aberto, encerrados e contestações) e o simulado passam todos por {@link #confirmar},
 * que pergunta à empresa e registra o recebimento, a expiração ou a contestação. As chamadas à
 * empresa ficam fora de transação; o que grava, em transações curtas (diagnóstico de 27/10, B6).
 */
@Service
class ConfirmacaoDePagamentoOnlineService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmacaoDePagamentoOnlineService.class);
    private static final int LOTE_DA_CONSULTA = 200;
    // Os cartões de 4 meses: uma academia grande passa de 200.
    private static final int LOTE_DAS_CONTESTACOES = 5000;

    private final EmpresaDePagamento empresa;
    private final PagamentoOnlineRepository repository;
    private final CobrancasParaPagamentoOnline cobrancas;
    private final Auditoria auditoria;
    private final PagamentosProperties propriedades;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    ConfirmacaoDePagamentoOnlineService(EmpresaDePagamento empresa, PagamentoOnlineRepository repository,
                                        CobrancasParaPagamentoOnline cobrancas, Auditoria auditoria,
                                        PagamentosProperties propriedades, PlatformTransactionManager transacoes,
                                        Clock relogio) {
        this.empresa = empresa;
        this.repository = repository;
        this.cobrancas = cobrancas;
        this.auditoria = auditoria;
        this.propriedades = propriedades;
        this.transacao = new TransactionTemplate(transacoes);
        this.relogio = relogio;
    }

    /**
     * Pergunta à empresa por este pagamento e registra o que ela disser. É o mesmo caminho para o
     * aviso, para a consulta periódica e para o simulado: o corpo do aviso nunca é a fonte.
     */
    void confirmar(String idExterno) {
        ProvedorDePagamento empresa = this.empresa.ligada();
        Optional<PagamentoOnline> lido = repository.porIdExterno(empresa.nome(), idExterno);
        if (lido.isEmpty()) {
            log.warn("Aviso de pagamento para um id desconhecido na empresa {}", empresa.nome());
            return;
        }
        PagamentoOnline pagamento = lido.get();
        if (pagamento.getSituacao().contestavel()) {
            // Já recebido: a única novidade possível é a contestação (ADR 0037).
            if (empresa.consultar(idExterno).estado() == Estado.CONTESTADO) {
                transacao.executeWithoutResult(t -> registrarContestacao(pagamento.getId()));
            }
            return;
        }
        if (!pagamento.getSituacao().recebe()) {
            return;
        }
        Situacao situacao = empresa.consultar(idExterno);
        switch (situacao.estado()) {
            case PAGO -> transacao.executeWithoutResult(t -> registrarRecebido(pagamento.getId(), situacao));
            // Contestado sem nunca ter sido recebido aqui (pago e contestado entre duas consultas): o
            // dinheiro já voltou ao pagador, e não pagou nada; sai como encerrado.
            case ENCERRADO, CONTESTADO -> transacao.executeWithoutResult(t -> repository.travar(pagamento.getId())
                    .filter(p -> p.getSituacao() == PagamentoOnline.Situacao.AGUARDANDO)
                    .ifPresent(p -> {
                        p.expirar();
                        repository.atualizar(p);
                    }));
            case AGUARDANDO -> {
                if (pagamento.getSituacao() == PagamentoOnline.Situacao.AGUARDANDO) {
                    expirarSePassouDaTolerancia(empresa, pagamento);
                } else {
                    // Encerrado aqui, mas em aberto lá: o cancelamento falhou (ADR 0036). De novo.
                    EmpresaDePagamento.cancelarNaEmpresa(empresa, idExterno);
                }
            }
        }
    }

    /**
     * A linha do pagamento online primeiro, depois a cobrança (ADR 0034): o aviso e a consulta
     * periódica ao mesmo tempo, um espera o outro, e o segundo vê que já foi registrado.
     */
    private void registrarRecebido(UUID id, Situacao situacao) {
        PagamentoOnline pagamento = repository.travar(id).orElseThrow();
        if (!pagamento.getSituacao().recebe()) {
            return;
        }
        Map<String, Object> antes = Auditoria.campos("situacao", pagamento.getSituacao().name());
        BigDecimal pago = situacao.valorPago();
        // Pago a mais (decisão do dono, ADR 0035): a cobrança recebe só o combinado, e a sobra volta.
        boolean aMais = pago.compareTo(pagamento.total()) > 0;
        ResultadoDoRecebimento resultado = cobrancas.receber(pagamento.getCobrancaId(),
                new ValorCombinado(pagamento.getValor(), pagamento.getAcrescimo(), pagamento.getJuros()),
                aMais ? pagamento.total() : pago, formaNaCobranca(pagamento.getForma()), pagamento.getGeradoPor());
        Instant em = situacao.pagoEm() == null ? relogio.instant() : situacao.pagoEm();
        switch (resultado) {
            case RECEBIDO -> {
                if (aMais) {
                    pagamento.pagoComSobra(pago, em, "Pago a mais: recebido " + pago + ", e o combinado era "
                            + pagamento.total() + "; a cobrança ficou paga e a sobra volta");
                } else {
                    pagamento.receber(pago, em);
                }
            }
            case NAO_ESTA_EM_ABERTO -> pagamento.aDevolver(pago, em,
                    "A cobrança já não estava em aberto (paga por outro meio ou cancelada)");
            case VALOR_DIFERENTE -> pagamento.aDevolver(pago, em,
                    "Recebido " + pago + ", e o combinado era " + pagamento.total());
        }
        repository.atualizar(pagamento);
        auditoria.registrar(PAGAMENTO_ONLINE, pagamento.getId(), pagamento.getSituacao().name(), antes,
                Auditoria.campos("situacao", pagamento.getSituacao().name(), "recebido", pagamento.getRecebido(),
                        "cobranca", pagamento.getCobrancaId().toString(),
                        "motivo", pagamento.getMotivoDaDevolucao(), "aDevolver", pagamento.getValorADevolver()));
    }

    /**
     * A contestação (ADR 0037): o pagador contestou no banco, que devolveu o dinheiro a ele. Se o
     * dinheiro tinha pago a cobrança (pago, ou pago a mais), ela volta em aberto (decisão do dono),
     * com o estorno registrado pelo login do aluno, como o recebimento. A linha do pagamento
     * online primeiro, depois a cobrança.
     */
    private void registrarContestacao(UUID id) {
        PagamentoOnline pagamento = repository.travar(id).orElseThrow();
        if (!pagamento.getSituacao().contestavel()) {
            return;
        }
        Map<String, Object> antes = Auditoria.campos("situacao", pagamento.getSituacao().name());
        boolean reabriu = pagamento.pagouACobrancaAntesDaContestacao()
                && cobrancas.estornarPorContestacao(pagamento.getCobrancaId(), formaNaCobranca(pagamento.getForma()),
                "Contestação no banco (" + pagamento.getForma().nome() + "): o dinheiro voltou ao aluno",
                pagamento.getGeradoPor());
        pagamento.contestar(relogio.instant(), reabriu);
        repository.atualizar(pagamento);
        auditoria.registrar(PAGAMENTO_ONLINE, id, "CONTESTADO", antes,
                Auditoria.campos("situacao", "CONTESTADO", "cobrancaReaberta", reabriu,
                        "devolvidoAntes", pagamento.getDevolvidoEm() != null));
    }

    /**
     * A consulta das contestações (ADR 0037), uma vez por dia: os cartões recebidos nos últimos
     * meses. A contestação chega semanas depois, e sem o site na internet o aviso nunca chega.
     */
    void consultarContestacoes() {
        if (empresa.talvez().isEmpty()) {
            return;
        }
        Instant desde = relogio.instant().minus(propriedades.janelaDasContestacoes());
        for (PagamentoOnline pagamento : repository.cartoesRecebidos(empresa.talvez().get().nome(), desde,
                LOTE_DAS_CONTESTACOES)) {
            try {
                confirmar(pagamento.getIdExterno());
            } catch (RuntimeException e) {
                log.warn("Consulta da contestação do pagamento online {} falhou: {}", pagamento.getId(), e.getMessage());
            }
        }
    }

    /** As contestações dos últimos 90 dias (a lista do ADMIN). */
    List<PagamentoOnline> contestacoes() {
        return repository.contestados(relogio.instant().minus(Duration.ofDays(90)));
    }

    /** Só com o provedor simulado (ADMIN): o pagador "contesta", e o resto é o caminho de verdade. */
    void simularContestacao(UUID id) {
        if (!(empresa.talvez().orElse(null) instanceof ProvedorSimulado simulado)) {
            throw new PagamentoOnlineNaoEncontradoException("Simulação desligada");
        }
        PagamentoOnline pagamento = repository.buscar(id)
                .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Pagamento online não encontrado"));
        if (!pagamento.getSituacao().contestavel()) {
            throw new PagamentoOnlineRecusadoException("Só um pagamento recebido pode ser contestado");
        }
        simulado.simularContestacao(pagamento.getIdExterno());
        confirmar(pagamento.getIdExterno());
    }

    /** A consulta periódica: os aguardando, um por um; a falha de um não para os outros. */
    void consultarAguardando() {
        if (empresa.talvez().isEmpty()) {
            return;
        }
        for (PagamentoOnline pagamento : repository.aguardando(empresa.talvez().get().nome(), LOTE_DA_CONSULTA)) {
            try {
                confirmar(pagamento.getIdExterno());
            } catch (RuntimeException e) {
                log.warn("Consulta do pagamento online {} falhou: {}", pagamento.getId(), e.getMessage());
            }
        }
    }

    /**
     * A consulta dos encerrados (ADR 0036): os cancelados e expirados dos últimos dias, que ainda
     * podem ter sido pagos (no último segundo, ou porque o cancelamento na empresa falhou). A
     * falha de um não para os outros.
     */
    void consultarEncerrados() {
        if (empresa.talvez().isEmpty()) {
            return;
        }
        Instant desde = relogio.instant().minus(propriedades.janelaDosEncerrados());
        for (PagamentoOnline pagamento : repository.encerradosRecentes(empresa.talvez().get().nome(), desde,
                LOTE_DA_CONSULTA)) {
            try {
                confirmar(pagamento.getIdExterno());
            } catch (RuntimeException e) {
                log.warn("Consulta do pagamento online encerrado {} falhou: {}", pagamento.getId(), e.getMessage());
            }
        }
    }

    /** Ainda aguardando na empresa, mas o prazo e a tolerância passaram: expira aqui e cancela lá. */
    private void expirarSePassouDaTolerancia(ProvedorDePagamento empresa, PagamentoOnline pagamento) {
        // O cartão, como o Pix: a resposta da empresa chega em minutos.
        Instant limite = pagamento.getValidoAte().plus(pagamento.getForma() == Forma.BOLETO
                ? propriedades.toleranciaDoBoleto() : propriedades.toleranciaDoPix());
        if (pagamento.getSituacao() != PagamentoOnline.Situacao.AGUARDANDO || relogio.instant().isBefore(limite)) {
            return;
        }
        EmpresaDePagamento.cancelarNaEmpresa(empresa, pagamento.getIdExterno());
        transacao.executeWithoutResult(t -> repository.travar(pagamento.getId())
                .filter(p -> p.getSituacao() == PagamentoOnline.Situacao.AGUARDANDO)
                .ifPresent(p -> {
                    p.expirar();
                    repository.atualizar(p);
                }));
    }

    /** Só com o provedor simulado: o aluno "paga" o que gerou, e o resto é o caminho de verdade. */
    void simularPagamento(UUID alunoId, UUID id) {
        if (!(empresa.talvez().orElse(null) instanceof ProvedorSimulado simulado)) {
            throw new PagamentoOnlineNaoEncontradoException("Simulação desligada");
        }
        PagamentoOnline pagamento = repository.buscar(id).filter(p -> p.getAlunoId().equals(alunoId))
                .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Pagamento online não encontrado"));
        simulado.simularPagamento(pagamento.getIdExterno(), null);
        confirmar(pagamento.getIdExterno());
    }

    /** O aviso da empresa: confere a autenticidade e consulta cada id citado. */
    void receberAviso(Map<String, String> cabecalhos, byte[] corpo) {
        ProvedorDePagamento empresa = this.empresa.ligada();
        for (String idExterno : empresa.lerAviso(cabecalhos, corpo)) {
            confirmar(idExterno);
        }
    }

    private static FormaOnline formaNaCobranca(Forma forma) {
        return switch (forma) {
            case PIX -> FormaOnline.PIX;
            case BOLETO -> FormaOnline.BOLETO;
            case CREDITO -> FormaOnline.CREDITO;
            case DEBITO -> FormaOnline.DEBITO;
        };
    }
}
