package br.com.academiagestao.pagamentos;

import br.com.academiagestao.alunos.AlunosParaOutrosModulos;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.CobrancaEmAberto;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.ValorCombinado;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Gerado;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Pagador;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Pedido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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

/**
 * O que o aluno vê e gera (ADR 0034): as opções de pagamento online e o Pix, o boleto ou a página do
 * cartão. As chamadas à empresa ficam fora de transação (rede lenta não segura conexão do banco); o
 * que grava fica em transações curtas. A confirmação e a devolução têm services próprios
 * (diagnóstico de 27/10, B6).
 */
@Service
class PagamentoOnlineService {

    private static final Logger log = LoggerFactory.getLogger(PagamentoOnlineService.class);

    private final EmpresaDePagamento empresa;
    private final PagamentoOnlineRepository repository;
    private final ConfiguracaoDoPagamentoOnlineRepository configuracao;
    private final CobrancasParaPagamentoOnline cobrancas;
    private final AlunosParaOutrosModulos alunos;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    PagamentoOnlineService(EmpresaDePagamento empresa, PagamentoOnlineRepository repository,
                           ConfiguracaoDoPagamentoOnlineRepository configuracao, CobrancasParaPagamentoOnline cobrancas,
                           AlunosParaOutrosModulos alunos, PlatformTransactionManager transacoes, Clock relogio) {
        this.empresa = empresa;
        this.repository = repository;
        this.configuracao = configuracao;
        this.cobrancas = cobrancas;
        this.alunos = alunos;
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
        if (empresa.talvez().isEmpty()) {
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
        return new Opcoes(true, empresa.simulado(), empresa.disponivel(config, Forma.PIX),
                empresa.disponivel(config, Forma.BOLETO), empresa.disponivel(config, Forma.CREDITO),
                empresa.disponivel(config, Forma.DEBITO), pagaveis);
    }

    /**
     * O boleto para a tela do boleto simulado (a "página do banco" no modo de teste): só com o provedor simulado e só
     * boleto. {@code alunoId}: o do token do aluno, que só vê os dele; nulo para a recepção e o ADMIN, que geram no balcão.
     */
    PagamentoOnline boletoSimulado(UUID id, UUID alunoId) {
        if (!empresa.simulado()) {
            throw new PagamentoOnlineNaoEncontradoException("Simulação desligada");
        }
        return repository.buscar(id).filter(p -> p.getForma() == Forma.BOLETO)
                .filter(p -> alunoId == null || p.getAlunoId().equals(alunoId))
                .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Boleto não encontrado"));
    }

    /** O que o balcão pode gerar para o aluno pagar depois (ADR 0047): hoje, só o boleto. */
    record OpcoesDoBalcao(boolean boleto) {
    }

    OpcoesDoBalcao opcoesDoBalcao() {
        return new OpcoesDoBalcao(empresa.disponivel(configuracao.ler(), Forma.BOLETO));
    }

    /**
     * Gera (ou devolve o que ainda vale) um Pix ou boleto para uma cobrança do aluno. O valor é o
     * de hoje; se mudou desde o último gerado, o antigo é cancelado na empresa.
     */
    PagamentoOnline gerar(UUID alunoId, UUID usuarioId, UUID cobrancaId, Forma forma) {
        return gerar(alunoId, usuarioId, cobrancaId, forma, false);
    }

    /**
     * O boleto gerado pela recepção ou pelo ADMIN para o aluno pagar depois (ADR 0047), o mesmo que o
     * aluno geraria no app. Vale para qualquer cobrança em aberto do aluno, como o recebimento no
     * balcão: o limite de adiantar (só as que vencem até hoje, ou a próxima) é do app.
     */
    PagamentoOnline gerarBoletoNoBalcao(UUID alunoId, UUID usuarioId, UUID cobrancaId) {
        return gerar(alunoId, usuarioId, cobrancaId, Forma.BOLETO, true);
    }

    private PagamentoOnline gerar(UUID alunoId, UUID usuarioId, UUID cobrancaId, Forma forma, boolean noBalcao) {
        ProvedorDePagamento empresa = this.empresa.ligada();
        ConfiguracaoDoPagamentoOnline config = configuracao.ler();
        if (!this.empresa.disponivel(config, forma)) {
            String nome = forma.nome();
            throw new PagamentoOnlineRecusadoException(Character.toUpperCase(nome.charAt(0)) + nome.substring(1)
                    + (noBalcao ? " não está disponível" : " não está disponível no app"));
        }
        Instant agora = relogio.instant();
        LocalDate hoje = Calendario.hoje(agora);
        List<CobrancaEmAberto> emAberto = cobrancas.emAberto(alunoId);
        CobrancaEmAberto cobranca = emAberto.stream().filter(c -> c.id().equals(cobrancaId)).findFirst()
                .orElseThrow(() -> new PagamentoOnlineNaoEncontradoException("Cobrança em aberto não encontrada"));
        if (!noBalcao && config.pagaveis(emAberto, hoje).stream().noneMatch(c -> c.id().equals(cobrancaId))) {
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
            EmpresaDePagamento.cancelarNaEmpresa(empresa, gerado.idExterno());
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
            EmpresaDePagamento.cancelarNaEmpresa(empresa, gerado.idExterno());
            throw e;
        }
        return novo;
    }

    /**
     * O que ainda vale. O valor fica o combinado: o boleto de uma cobrança vencida vale alguns
     * dias com valor fixo, e o Pix só vale até o fim do dia em que foi gerado (ADR 0034).
     */
    private static Optional<PagamentoOnline> valendo(List<PagamentoOnline> gerados, Forma forma, Instant agora) {
        return gerados.stream().filter(p -> p.getForma() == forma && p.vale(agora)).findFirst();
    }

    private void cancelar(ProvedorDePagamento empresa, PagamentoOnline antigo) {
        EmpresaDePagamento.cancelarNaEmpresa(empresa, antigo.getIdExterno());
        transacao.executeWithoutResult(t -> repository.travar(antigo.getId())
                .filter(p -> p.getSituacao() == PagamentoOnline.Situacao.AGUARDANDO)
                .ifPresent(p -> {
                    p.cancelar();
                    repository.atualizar(p);
                }));
    }

    private static String descricao(CobrancaEmAberto c) {
        return switch (c.tipo()) {
            case "MENSALIDADE" -> "Mensalidade " + c.parcela();
            case "MULTA" -> "Multa por sair antes";
            default -> "Plano à vista";
        };
    }
}
