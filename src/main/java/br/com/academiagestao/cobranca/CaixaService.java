package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Caixa;
import br.com.academiagestao.cobranca.dominio.Caixa.Fechamento;
import br.com.academiagestao.cobranca.dominio.Caixa.Reabertura;
import br.com.academiagestao.cobranca.dominio.CobrancaInvalidaException;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.MovimentoDoDia;
import br.com.academiagestao.cobranca.dominio.MovimentoDoDia.Movimento;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** Até um ano por relatório: o suficiente para conferir um exercício, sem uma consulta sem fim. */
    static final int PERIODO_MAXIMO_EM_DIAS = 366;
    /** Datas aceitas no relatório: fora disso, a conta do período estoura (era 500). */
    static final LocalDate PRIMEIRO_DIA = LocalDate.of(2000, 1, 1);
    static final LocalDate ULTIMO_DIA = LocalDate.of(2100, 12, 31);

    /**
     * O caixa com o movimento dele, por forma, e o histórico de fechamentos e reaberturas.
     * {@code hoje}: para o controller decidir o que a recepção vê.
     */
    record Consulta(Caixa caixa, MovimentoDoDia movimento, List<Fechamento> fechamentos, List<Reabertura> reaberturas,
                    LocalDate hoje) {
    }

    /**
     * Um dia do relatório. {@code caixa} nulo: um dia de antes do caixa (V35), com lançamentos
     * registrados sem ele.
     */
    record DiaDoRelatorio(LocalDate dia, Caixa caixa, MovimentoDoDia movimento, List<Fechamento> fechamentos,
                          List<Reabertura> reaberturas) {
    }

    /**
     * O período: os dias com caixa ou com lançamentos, o total por forma, a soma das
     * diferenças dos dias fechados (a do último fechamento de cada um) e a das aberturas.
     */
    record Relatorio(LocalDate de, LocalDate ate, List<DiaDoRelatorio> dias, MovimentoDoDia total,
                     BigDecimal diferencaDosFechados, BigDecimal diferencaDasAberturas) {
    }

    /**
     * Abre o caixa de hoje com o troco. Recusa se ficou um dia anterior aberto (esquecido ou
     * reaberto): nenhum dia fica sem fechamento. Grava com o troco o que ficou na gaveta no
     * fechamento anterior, para o ADMIN comparar (ADR 0046, item 8); a abertura é aceita mesmo
     * diferente, e às cegas.
     */
    @Transactional
    Consulta abrir(BigDecimal troco, UUID usuarioId) {
        Instant agora = relogio.instant();
        LocalDate hoje = Calendario.hoje(agora);
        repository.anteriorAberto(hoje).ifPresent(dia -> {
            throw new OperacaoDeCaixaRecusadaException("O caixa de " + dia
                    + " ainda está aberto: feche-o (com a contagem) antes de abrir o de hoje");
        });
        Caixa caixa = Caixa.abrir(hoje, troco, repository.trocoQueFicou(hoje).orElse(null), usuarioId, agora);
        try {
            repository.inserir(caixa);
        } catch (DuplicateKeyException e) {
            // Um caixa por dia (V35): já aberto antes, ou outra abertura ganhou a corrida.
            throw new OperacaoDeCaixaRecusadaException("O caixa de " + hoje
                    + " já foi aberto; se foi fechado, só o ADMIN reabre");
        }
        auditoria.registrar(CAIXA, caixa.getId(), "ABERTO", Auditoria.campos(),
                Auditoria.campos("dia", hoje.toString(), "troco", caixa.getTroco(),
                        "trocoEsperado", caixa.getTrocoEsperado()));
        return consulta(caixa);
    }

    /**
     * Fecha com o dinheiro contado. Trava o caixa antes de somar: um pagamento em andamento
     * termina antes e entra na conta; um que chega depois encontra o caixa fechado.
     */
    @Transactional
    Consulta fechar(LocalDate dia, BigDecimal contado, BigDecimal retirado, String justificativa, UUID usuarioId) {
        Caixa caixa = travar(dia);
        Movimento dinheiro = lancamentos.movimentoDoCaixa(caixa.getId()).porForma().get(MeioDePagamento.DINHEIRO);
        Fechamento fechamento = caixa.fechar(contado, retirado, dinheiro.recebido(), dinheiro.estornado(),
                justificativa, usuarioId, relogio.instant());
        repository.atualizarSituacao(caixa);
        repository.inserir(fechamento);
        auditoria.registrar(CAIXA, caixa.getId(), "FECHADO", Auditoria.campos("situacao", "ABERTO"),
                Auditoria.campos("situacao", "FECHADO", "dia", dia.toString(), "esperado", fechamento.esperado(),
                        "contado", fechamento.contado(), "retirado", fechamento.retirado(),
                        "diferenca", fechamento.diferenca(),
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

    /** Os dias ainda abertos: a recepção vê o que falta fechar. */
    @Transactional(readOnly = true)
    List<Caixa> abertos() {
        return repository.abertos();
    }

    /** Só o ADMIN (o controller confere): todos os dias e todos os fechamentos do período. */
    @Transactional(readOnly = true)
    Relatorio relatorio(LocalDate de, LocalDate ate) {
        if (de == null || ate == null || de.isAfter(ate)
                || ChronoUnit.DAYS.between(de, ate) + 1 > PERIODO_MAXIMO_EM_DIAS) {
            throw new CobrancaInvalidaException("o período vai de \"de\" até \"ate\", com até "
                    + PERIODO_MAXIMO_EM_DIAS + " dias");
        }
        if (de.isBefore(PRIMEIRO_DIA) || ate.isAfter(ULTIMO_DIA)) {
            throw new CobrancaInvalidaException("as datas do relatório vão de " + PRIMEIRO_DIA + " a " + ULTIMO_DIA);
        }
        Map<LocalDate, Caixa> caixas = new HashMap<>();
        repository.doPeriodo(de, ate).forEach(c -> caixas.put(c.getDia(), c));
        Map<UUID, List<Fechamento>> fechamentos = repository.fechamentosDoPeriodo(de, ate).stream()
                .collect(Collectors.groupingBy(Fechamento::caixaId));
        Map<UUID, List<Reabertura>> reaberturas = repository.reaberturasDoPeriodo(de, ate).stream()
                .collect(Collectors.groupingBy(Reabertura::caixaId));
        Map<LocalDate, MovimentoDoDia> movimentos = lancamentos.movimentoPorDia(de, ate);

        SortedSet<LocalDate> diasComAlgo = new TreeSet<>(caixas.keySet());
        diasComAlgo.addAll(movimentos.keySet());
        List<DiaDoRelatorio> dias = new ArrayList<>();
        MovimentoDoDia total = MovimentoDoDia.VAZIO;
        BigDecimal diferenca = new BigDecimal("0.00");
        BigDecimal diferencaDasAberturas = new BigDecimal("0.00");
        for (LocalDate dia : diasComAlgo) {
            Caixa caixa = caixas.get(dia);
            MovimentoDoDia movimento = movimentos.getOrDefault(dia, MovimentoDoDia.VAZIO);
            List<Fechamento> doDia = caixa == null ? List.of() : fechamentos.getOrDefault(caixa.getId(), List.of());
            dias.add(new DiaDoRelatorio(dia, caixa, movimento, doDia,
                    caixa == null ? List.of() : reaberturas.getOrDefault(caixa.getId(), List.of())));
            total = total.mais(movimento);
            if (caixa != null && caixa.getSituacao() == Caixa.Situacao.FECHADO) {
                diferenca = diferenca.add(doDia.getLast().diferenca());
            }
            if (caixa != null && caixa.diferencaNaAbertura() != null) {
                diferencaDasAberturas = diferencaDasAberturas.add(caixa.diferencaNaAbertura());
            }
        }
        return new Relatorio(de, ate, dias, total, diferenca, diferencaDasAberturas);
    }

    @Transactional(readOnly = true)
    Consulta doDia(LocalDate dia) {
        return consulta(repository.doDia(dia).orElseThrow(() -> new CaixaNaoEncontradoException(dia)));
    }

    private Caixa travar(LocalDate dia) {
        return repository.travar(dia).orElseThrow(() -> new CaixaNaoEncontradoException(dia));
    }

    private Consulta consulta(Caixa caixa) {
        return new Consulta(caixa, lancamentos.movimentoDoCaixa(caixa.getId()), repository.fechamentos(caixa.getId()),
                repository.reaberturas(caixa.getId()), Calendario.hoje(relogio.instant()));
    }
}
