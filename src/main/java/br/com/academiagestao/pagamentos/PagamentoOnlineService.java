package br.com.academiagestao.pagamentos;

import br.com.academiagestao.alunos.AlunosParaMatricula;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.CobrancaEmAberto;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.FormaOnline;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.ResultadoDoRecebimento;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.ValorCombinado;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Gerado;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Pagador;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Pedido;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Situacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.PAGAMENTO_ONLINE;

/**
 * Gerar, confirmar e expirar pagamentos online (ADR 0034). As chamadas à empresa ficam fora de
 * transação (rede lenta não segura conexão do banco); o que grava fica em transações curtas.
 */
@Service
class PagamentoOnlineService {

    private static final Logger log = LoggerFactory.getLogger(PagamentoOnlineService.class);
    private static final int LOTE_DA_CONSULTA = 200;

    private final Optional<ProvedorDePagamento> provedor;
    private final PagamentoOnlineRepository repository;
    private final ConfiguracaoDoPagamentoOnlineRepository configuracao;
    private final CobrancasParaPagamentoOnline cobrancas;
    private final AlunosParaMatricula alunos;
    private final Auditoria auditoria;
    private final PagamentosProperties propriedades;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    PagamentoOnlineService(ObjectProvider<ProvedorDePagamento> provedor, PagamentoOnlineRepository repository,
                           ConfiguracaoDoPagamentoOnlineRepository configuracao, CobrancasParaPagamentoOnline cobrancas,
                           AlunosParaMatricula alunos, Auditoria auditoria, PagamentosProperties propriedades,
                           PlatformTransactionManager transacoes, Clock relogio) {
        this.provedor = Optional.ofNullable(provedor.getIfAvailable());
        this.repository = repository;
        this.configuracao = configuracao;
        this.cobrancas = cobrancas;
        this.alunos = alunos;
        this.auditoria = auditoria;
        this.propriedades = propriedades;
        this.transacao = new TransactionTemplate(transacoes);
        this.relogio = relogio;
    }

    /** Uma cobrança que o aluno pode pagar hoje, com o Pix e o boleto que ele já gerou (se ainda valem). */
    record Pagavel(CobrancaEmAberto cobranca, Optional<PagamentoOnline> pix, Optional<PagamentoOnline> boleto) {
    }

    /** O que o app mostra: se há pagamento online, quais formas, e o que pagar. */
    record Opcoes(boolean ligado, boolean simulado, boolean pix, boolean boleto, List<Pagavel> pagaveis) {
    }

    Opcoes opcoes(UUID alunoId) {
        if (provedor.isEmpty()) {
            return new Opcoes(false, false, false, false, List.of());
        }
        ConfiguracaoDoPagamentoOnline config = configuracao.ler();
        Instant agora = relogio.instant();
        Map<UUID, List<PagamentoOnline>> gerados = repository.aguardandoDoAluno(alunoId).stream()
                .collect(Collectors.groupingBy(PagamentoOnline::getCobrancaId));
        List<Pagavel> pagaveis = config.pagaveis(cobrancas.emAberto(alunoId), Calendario.hoje(agora)).stream()
                .map(c -> {
                    List<PagamentoOnline> daCobranca = gerados.getOrDefault(c.id(), List.of());
                    return new Pagavel(c, valendo(daCobranca, Forma.PIX, agora),
                            valendo(daCobranca, Forma.BOLETO, agora));
                })
                .toList();
        return new Opcoes(true, simulado(), config.pixLigado(), config.boletoLigado(), pagaveis);
    }

    /**
     * Gera (ou devolve o que ainda vale) um Pix ou boleto para uma cobrança do aluno. O valor é o
     * de hoje; se mudou desde o último gerado, o antigo é cancelado na empresa.
     */
    PagamentoOnline gerar(UUID alunoId, UUID usuarioId, UUID cobrancaId, Forma forma) {
        ProvedorDePagamento empresa = ligado();
        ConfiguracaoDoPagamentoOnline config = configuracao.ler();
        if (!config.ligada(forma)) {
            throw new PagamentoOnlineRecusadoException((forma == Forma.PIX ? "O Pix" : "O boleto")
                    + " não está disponível no app");
        }
        Instant agora = relogio.instant();
        LocalDate hoje = Calendario.hoje(agora);
        List<CobrancaEmAberto> emAberto = cobrancas.emAberto(alunoId);
        CobrancaEmAberto cobranca = emAberto.stream().filter(c -> c.id().equals(cobrancaId)).findFirst()
                .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Cobrança em aberto não encontrada"));
        if (config.pagaveis(emAberto, hoje).stream().noneMatch(c -> c.id().equals(cobrancaId))) {
            throw new PagamentoOnlineRecusadoException("Esta cobrança ainda não pode ser paga pelo app");
        }

        List<PagamentoOnline> aguardando = repository.aguardandoDaCobranca(cobrancaId).stream()
                .filter(p -> p.getForma() == forma).toList();
        Optional<PagamentoOnline> valendo = valendo(aguardando, forma, agora);
        if (valendo.isPresent()) {
            return valendo.get();
        }
        // O prazo do antigo passou (o Pix de ontem, por exemplo): sai, na empresa e aqui.
        aguardando.forEach(antigo -> cancelar(empresa, antigo));

        // O Pix vale hoje; o boleto, até o vencimento, ou alguns dias se a cobrança já venceu.
        LocalDate vencimento = forma == Forma.PIX ? hoje
                : cobranca.vencimento().isBefore(hoje) ? hoje.plusDays(config.diasDoBoleto())
                : cobranca.vencimento();
        Instant validoAte = vencimento.plusDays(1).atStartOfDay(Calendario.FUSO).toInstant();
        UUID id = UUID.randomUUID();
        ValorCombinado devido = cobranca.devido();
        Pagador pagador = alunos.pagador(alunoId)
                .map(p -> new Pagador(p.nome(), p.cpf(), p.email()))
                .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Aluno não encontrado"));
        Gerado gerado = empresa.gerar(new Pedido(id, forma, devido.total(), validoAte, vencimento,
                descricao(cobranca), pagador));
        PagamentoOnline novo = PagamentoOnline.novo(id, cobrancaId, alunoId, forma, empresa.nome(), gerado,
                devido.valor(), devido.acrescimo(), devido.juros(), validoAte, usuarioId, agora);
        try {
            transacao.executeWithoutResult(t -> repository.inserir(novo));
        } catch (RuntimeException e) {
            // Gerado na empresa e não gravado aqui: cancela lá para ninguém pagar o que o sistema não conhece.
            cancelarNaEmpresa(empresa, gerado.idExterno());
            throw e;
        }
        return novo;
    }

    /**
     * Pergunta à empresa por este pagamento e registra o que ela disser. É o mesmo caminho para o
     * aviso, para a consulta periódica e para o simulado: o corpo do aviso nunca é a fonte.
     */
    void confirmar(String idExterno) {
        ProvedorDePagamento empresa = ligado();
        Optional<PagamentoOnline> lido = repository.porIdExterno(empresa.nome(), idExterno);
        if (lido.isEmpty()) {
            log.warn("Aviso de pagamento para um id desconhecido na empresa {}", empresa.nome());
            return;
        }
        PagamentoOnline pagamento = lido.get();
        if (!pagamento.getSituacao().recebe()) {
            return;
        }
        Situacao situacao = empresa.consultar(idExterno);
        switch (situacao.estado()) {
            case PAGO -> transacao.executeWithoutResult(t -> registrarRecebido(pagamento.getId(), situacao));
            case ENCERRADO -> transacao.executeWithoutResult(t -> repository.travar(pagamento.getId())
                    .filter(p -> p.getSituacao() == PagamentoOnline.Situacao.AGUARDANDO)
                    .ifPresent(p -> {
                        p.expirar();
                        repository.atualizar(p);
                    }));
            case AGUARDANDO -> expirarSePassouDaTolerancia(empresa, pagamento);
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
        ResultadoDoRecebimento resultado = cobrancas.receber(pagamento.getCobrancaId(),
                new ValorCombinado(pagamento.getValor(), pagamento.getAcrescimo(), pagamento.getJuros()),
                situacao.valorPago(), pagamento.getForma() == Forma.PIX ? FormaOnline.PIX : FormaOnline.BOLETO,
                pagamento.getGeradoPor());
        Instant em = situacao.pagoEm() == null ? relogio.instant() : situacao.pagoEm();
        switch (resultado) {
            case RECEBIDO -> pagamento.receber(situacao.valorPago(), em);
            case NAO_ESTA_EM_ABERTO -> pagamento.aDevolver(situacao.valorPago(), em,
                    "A cobrança já não estava em aberto (paga por outro meio ou cancelada)");
            case VALOR_DIFERENTE -> pagamento.aDevolver(situacao.valorPago(), em,
                    "Recebido " + situacao.valorPago() + ", e o combinado era " + pagamento.total());
        }
        repository.atualizar(pagamento);
        auditoria.registrar(PAGAMENTO_ONLINE, pagamento.getId(), pagamento.getSituacao().name(), antes,
                Auditoria.campos("situacao", pagamento.getSituacao().name(), "recebido", pagamento.getRecebido(),
                        "cobranca", pagamento.getCobrancaId().toString(),
                        "motivo", pagamento.getMotivoDaDevolucao()));
    }

    /** A consulta periódica: os aguardando, um por um; a falha de um não para os outros. */
    void consultarAguardando() {
        if (provedor.isEmpty()) {
            return;
        }
        for (PagamentoOnline pagamento : repository.aguardando(provedor.get().nome(), LOTE_DA_CONSULTA)) {
            try {
                confirmar(pagamento.getIdExterno());
            } catch (RuntimeException e) {
                log.warn("Consulta do pagamento online {} falhou: {}", pagamento.getId(), e.getMessage());
            }
        }
    }

    /** Ainda aguardando na empresa, mas o prazo e a tolerância passaram: expira aqui e cancela lá. */
    private void expirarSePassouDaTolerancia(ProvedorDePagamento empresa, PagamentoOnline pagamento) {
        Instant limite = pagamento.getValidoAte().plus(pagamento.getForma() == Forma.PIX
                ? propriedades.toleranciaDoPix() : propriedades.toleranciaDoBoleto());
        if (pagamento.getSituacao() != PagamentoOnline.Situacao.AGUARDANDO || relogio.instant().isBefore(limite)) {
            return;
        }
        cancelarNaEmpresa(empresa, pagamento.getIdExterno());
        transacao.executeWithoutResult(t -> repository.travar(pagamento.getId())
                .filter(p -> p.getSituacao() == PagamentoOnline.Situacao.AGUARDANDO)
                .ifPresent(p -> {
                    p.expirar();
                    repository.atualizar(p);
                }));
    }

    /** Só o ADMIN: o dinheiro que chegou sem ter o que pagar. */
    List<PagamentoOnline> aDevolver() {
        return repository.aDevolver();
    }

    /** O ADMIN devolveu pelo painel da empresa. */
    PagamentoOnline marcarDevolvido(UUID id, UUID usuarioId) {
        return transacao.execute(t -> {
            PagamentoOnline pagamento = repository.travar(id)
                    .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Pagamento online não encontrado"));
            if (pagamento.getSituacao() != PagamentoOnline.Situacao.A_DEVOLVER) {
                throw new PagamentoOnlineRecusadoException("Só o que está a devolver pode ser marcado como devolvido");
            }
            pagamento.devolver(usuarioId, relogio.instant());
            repository.atualizar(pagamento);
            auditoria.registrar(PAGAMENTO_ONLINE, id, "DEVOLVIDO", Auditoria.campos("situacao", "A_DEVOLVER"),
                    Auditoria.campos("situacao", "DEVOLVIDO", "recebido", pagamento.getRecebido()));
            return pagamento;
        });
    }

    /** Só com o provedor simulado: o aluno "paga" o que gerou, e o resto é o caminho de verdade. */
    void simularPagamento(UUID alunoId, UUID id) {
        if (!(provedor.orElse(null) instanceof ProvedorSimulado simulado)) {
            throw new PagamentoOnlineNaoEncontradoException("Simulação desligada");
        }
        PagamentoOnline pagamento = repository.buscar(id).filter(p -> p.getAlunoId().equals(alunoId))
                .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Pagamento online não encontrado"));
        simulado.simularPagamento(pagamento.getIdExterno(), null);
        confirmar(pagamento.getIdExterno());
    }

    /** O aviso da empresa: confere a autenticidade e consulta cada id citado. */
    void receberAviso(Map<String, String> cabecalhos, byte[] corpo) {
        ProvedorDePagamento empresa = ligado();
        for (String idExterno : empresa.lerAviso(cabecalhos, corpo)) {
            confirmar(idExterno);
        }
    }

    /** O nome da empresa ligada, ou {@code desligado}. */
    String provedorLigado() {
        return provedor.map(ProvedorDePagamento::nome).orElse(PagamentosProperties.DESLIGADO);
    }

    boolean simulado() {
        return provedor.filter(p -> p instanceof ProvedorSimulado).isPresent();
    }

    private ProvedorDePagamento ligado() {
        return provedor.orElseThrow(() -> new PagamentoOnlineRecusadoException("O pagamento online está desligado"));
    }

    /**
     * O que ainda vale. O valor fica o combinado: o boleto de uma cobrança vencida vale alguns
     * dias com valor fixo, e o Pix só vale até o fim do dia em que foi gerado (ADR 0034).
     */
    private static Optional<PagamentoOnline> valendo(List<PagamentoOnline> gerados, Forma forma, Instant agora) {
        return gerados.stream().filter(p -> p.getForma() == forma && p.vale(agora)).findFirst();
    }

    private void cancelar(ProvedorDePagamento empresa, PagamentoOnline antigo) {
        cancelarNaEmpresa(empresa, antigo.getIdExterno());
        transacao.executeWithoutResult(t -> repository.travar(antigo.getId())
                .filter(p -> p.getSituacao() == PagamentoOnline.Situacao.AGUARDANDO)
                .ifPresent(p -> {
                    p.cancelar();
                    repository.atualizar(p);
                }));
    }

    private static void cancelarNaEmpresa(ProvedorDePagamento empresa, String idExterno) {
        try {
            empresa.cancelar(idExterno);
        } catch (RuntimeException e) {
            log.warn("Não foi possível cancelar na empresa {} o pagamento {}: {}", empresa.nome(), idExterno,
                    e.getMessage());
        }
    }

    private static String descricao(CobrancaEmAberto c) {
        return switch (c.tipo()) {
            case "MENSALIDADE" -> "Mensalidade " + c.parcela();
            case "MULTA" -> "Multa por sair antes";
            default -> "Plano à vista";
        };
    }
}
