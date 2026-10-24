package br.com.academiagestao.pagamentos;

import br.com.academiagestao.alunos.AlunosParaMatricula;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.CobrancaEmAberto;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.FormaOnline;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.ResultadoDoRecebimento;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.ValorCombinado;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Devolucao;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Gerado;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Pagador;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Pedido;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Situacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
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

    /** Uma cobrança que o aluno pode pagar hoje, com o que ele já gerou em cada forma (se ainda vale). */
    record Pagavel(CobrancaEmAberto cobranca, Optional<PagamentoOnline> pix, Optional<PagamentoOnline> boleto,
                   Optional<PagamentoOnline> credito, Optional<PagamentoOnline> debito) {
    }

    /** O que o app mostra: se há pagamento online, quais formas (ligadas e que a empresa tem), e o que pagar. */
    record Opcoes(boolean ligado, boolean simulado, boolean pix, boolean boleto, boolean credito, boolean debito,
                  List<Pagavel> pagaveis) {
    }

    Opcoes opcoes(UUID alunoId) {
        if (provedor.isEmpty()) {
            return new Opcoes(false, false, false, false, false, false, List.of());
        }
        ConfiguracaoDoPagamentoOnline config = configuracao.ler();
        Instant agora = relogio.instant();
        Map<UUID, List<PagamentoOnline>> gerados = repository.aguardandoDoAluno(alunoId).stream()
                .collect(Collectors.groupingBy(PagamentoOnline::getCobrancaId));
        List<Pagavel> pagaveis = config.pagaveis(cobrancas.emAberto(alunoId), Calendario.hoje(agora)).stream()
                .map(c -> {
                    List<PagamentoOnline> daCobranca = gerados.getOrDefault(c.id(), List.of());
                    return new Pagavel(c, valendo(daCobranca, Forma.PIX, agora),
                            valendo(daCobranca, Forma.BOLETO, agora), valendo(daCobranca, Forma.CREDITO, agora),
                            valendo(daCobranca, Forma.DEBITO, agora));
                })
                .toList();
        return new Opcoes(true, simulado(), disponivel(config, Forma.PIX), disponivel(config, Forma.BOLETO),
                disponivel(config, Forma.CREDITO), disponivel(config, Forma.DEBITO), pagaveis);
    }

    /**
     * Gera (ou devolve o que ainda vale) um Pix ou boleto para uma cobrança do aluno. O valor é o
     * de hoje; se mudou desde o último gerado, o antigo é cancelado na empresa.
     */
    PagamentoOnline gerar(UUID alunoId, UUID usuarioId, UUID cobrancaId, Forma forma) {
        ProvedorDePagamento empresa = ligado();
        ConfiguracaoDoPagamentoOnline config = configuracao.ler();
        if (!disponivel(config, forma)) {
            String nome = forma.nome();
            throw new PagamentoOnlineRecusadoException(Character.toUpperCase(nome.charAt(0)) + nome.substring(1)
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

        // O Pix e a página do cartão valem hoje; o boleto, até o vencimento, ou alguns dias se a
        // cobrança já venceu.
        LocalDate vencimento = forma != Forma.BOLETO ? hoje
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
        } catch (DuplicateKeyException e) {
            // Dois pedidos ao mesmo tempo (ADR 0035): o outro gravou primeiro. O deste sai da
            // empresa, e o aluno recebe o que ficou. E fica gravado como CANCELADO (ADR 0036): se
            // o cancelamento lá falhou e alguém pagar, o dinheiro é reconhecido (paga ou fica a devolver).
            cancelarNaEmpresa(empresa, gerado.idExterno());
            novo.cancelar();
            try {
                transacao.executeWithoutResult(t -> repository.inserir(novo));
            } catch (RuntimeException naoGravou) {
                log.warn("O pagamento {} da empresa {} ficou fora do sistema: {}", gerado.idExterno(), empresa.nome(),
                        naoGravou.getMessage());
            }
            return valendo(repository.aguardandoDaCobranca(cobrancaId), forma, agora).orElseThrow(() -> e);
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
            case AGUARDANDO -> {
                if (pagamento.getSituacao() == PagamentoOnline.Situacao.AGUARDANDO) {
                    expirarSePassouDaTolerancia(empresa, pagamento);
                } else {
                    // Encerrado aqui, mas em aberto lá: o cancelamento falhou (ADR 0036). De novo.
                    cancelarNaEmpresa(empresa, idExterno);
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

    /**
     * A consulta dos encerrados (ADR 0036): os cancelados e expirados dos últimos dias, que ainda
     * podem ter sido pagos (no último segundo, ou porque o cancelamento na empresa falhou). A
     * falha de um não para os outros.
     */
    void consultarEncerrados() {
        if (provedor.isEmpty()) {
            return;
        }
        Instant desde = relogio.instant().minus(propriedades.janelaDosEncerrados());
        for (PagamentoOnline pagamento : repository.encerradosRecentes(provedor.get().nome(), desde, LOTE_DA_CONSULTA)) {
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

    /**
     * O ADMIN confirma, e o sistema pede a devolução à empresa (decisão do dono, ADR 0035). A
     * chamada fica fora de transação; a chave da devolução é o id do pagamento, então repetir
     * (clique duplo, resposta perdida na rede, DEVOLVENDO parado) não devolve duas vezes. Antes
     * da chamada, o pagamento vira DEVOLVENDO, e "por fora" é recusado (ADR 0036).
     */
    PagamentoOnline devolverPelaEmpresa(UUID id, UUID usuarioId) {
        ProvedorDePagamento empresa = ligado();
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
        return provedor.filter(p -> p.nome().equals(pagamento.getProvedor()) && p.devolve(pagamento.getForma()))
                .isPresent();
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

    /** As formas que a empresa ligada oferece (nenhuma, desligado). */
    List<Forma> formasDaEmpresa() {
        return provedor.map(p -> Arrays.stream(Forma.values()).filter(p::oferece).toList()).orElse(List.of());
    }

    /** Ligada pelo ADMIN e oferecida pela empresa. */
    private boolean disponivel(ConfiguracaoDoPagamentoOnline config, Forma forma) {
        return config.ligada(forma) && provedor.filter(p -> p.oferece(forma)).isPresent();
    }

    private static FormaOnline formaNaCobranca(Forma forma) {
        return switch (forma) {
            case PIX -> FormaOnline.PIX;
            case BOLETO -> FormaOnline.BOLETO;
            case CREDITO -> FormaOnline.CREDITO;
            case DEBITO -> FormaOnline.DEBITO;
        };
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
