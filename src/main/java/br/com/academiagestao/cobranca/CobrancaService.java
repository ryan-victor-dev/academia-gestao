package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Caixa;
import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.Lancamento;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.OperacaoDeCaixaRecusadaException;
import br.com.academiagestao.cobranca.dominio.Pagamento;
import br.com.academiagestao.cobranca.dominio.RegrasDeAtraso;
import br.com.academiagestao.cobranca.dominio.StatusCobranca;
import br.com.academiagestao.cobranca.dominio.ValorDevido;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.compartilhado.Pagina;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.COBRANCA;

/**
 * Consultar, pagar, estornar e cancelar cobranças (ADR 0024). Cada operação trava a cobrança
 * e vai para a trilha na mesma transação; pagamentos e estornos também vão para o registro de
 * lançamentos (ADR 0025), no caixa aberto do dia (ADR 0031).
 */
@Service
class CobrancaService {

    private final CobrancaRepository repository;
    private final LancamentoRepository lancamentos;
    private final CaixaRepository caixas;
    private final ConfiguracaoDaCobrancaRepository configuracao;
    private final Auditoria auditoria;
    private final Clock relogio;

    CobrancaService(CobrancaRepository repository, LancamentoRepository lancamentos, CaixaRepository caixas,
                    ConfiguracaoDaCobrancaRepository configuracao, Auditoria auditoria, Clock relogio) {
        this.repository = repository;
        this.lancamentos = lancamentos;
        this.caixas = caixas;
        this.configuracao = configuracao;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /** Uma cobrança com o devido hoje (só se em aberto) e a data de referência. */
    record Consulta(Cobranca cobranca, Optional<ValorDevido> devido, LocalDate hoje) {
    }

    @Transactional(readOnly = true)
    List<Consulta> doAluno(UUID alunoId) {
        RegrasDeAtraso regras = configuracao.ler().regras();
        LocalDate hoje = hoje();
        return repository.doAluno(alunoId).stream().map(c -> consulta(c, regras, hoje)).toList();
    }

    @Transactional(readOnly = true)
    Consulta buscar(UUID id) {
        Cobranca cobranca = repository.buscar(id).orElseThrow(() -> new CobrancaNaoEncontradaException(id));
        return consulta(cobranca, configuracao.ler().regras(), hoje());
    }

    /** Pagamentos e estornos da cobrança, na ordem em que aconteceram. */
    @Transactional(readOnly = true)
    List<Lancamento> lancamentos(UUID id) {
        repository.buscar(id).orElseThrow(() -> new CobrancaNaoEncontradaException(id));
        return lancamentos.daCobranca(id);
    }

    /** As em aberto já vencidas de todos os alunos, da mais antiga para a mais nova. */
    @Transactional(readOnly = true)
    Pagina<Consulta> atrasadas(int pagina, int tamanho) {
        RegrasDeAtraso regras = configuracao.ler().regras();
        LocalDate hoje = hoje();
        long total = repository.contarAtrasadas(hoje);
        List<Consulta> conteudo = repository.atrasadas(hoje, pagina, tamanho).stream()
                .map(c -> consulta(c, regras, hoje)).toList();
        return new Pagina<>(conteudo, pagina, tamanho, total, (int) ((total + tamanho - 1) / tamanho));
    }

    /**
     * Só o valor exato devido hoje; outro valor é recusado com o devido (ADR 0024). Só com o
     * caixa de hoje aberto (ADR 0031).
     */
    @Transactional
    Consulta pagar(UUID id, BigDecimal valor, MeioDePagamento meio, UUID usuarioId) {
        Instant agora = relogio.instant();
        LocalDate hoje = Calendario.hoje(agora);
        Caixa caixa = caixaQueRecebe(hoje);
        Cobranca cobranca = travar(id);
        Map<String, Object> antes = retrato(cobranca);
        RegrasDeAtraso regras = configuracao.ler().regras();
        cobranca.pagar(regras, hoje, valor, meio, usuarioId, agora);
        Consulta paga = gravar(cobranca, "PAGA", antes, null, regras, hoje);
        lancamentos.inserir(Lancamento.doPagamento(cobranca, caixa.getId()));
        return paga;
    }

    /**
     * Só o ADMIN: a cobrança volta a ficar em aberto (ou cancelada, se já saiu do cronograma,
     * ADR 0026), e o pagamento continua no registro, com um lançamento de estorno ao lado (ADR 0025).
     * O dinheiro sai do caixa de hoje, que precisa estar aberto (ADR 0031).
     */
    @Transactional
    Consulta estornar(UUID id, String motivo, UUID usuarioId) {
        String motivoLimpo = Cobranca.motivoValido(motivo);
        Instant agora = relogio.instant();
        LocalDate hoje = Calendario.hoje(agora);
        Caixa caixa = caixaQueRecebe(hoje);
        Cobranca cobranca = travar(id);
        Map<String, Object> antes = retrato(cobranca);
        cobranca.estornar(hoje, usuarioId, agora);
        Lancamento estorno = lancamentos.pagamentoEmVigor(id)
                .orElseThrow(() -> new IllegalStateException("cobrança paga sem o lançamento do pagamento: " + id))
                .estornar(motivoLimpo, usuarioId, agora, caixa.getId());
        Consulta estornada = gravar(cobranca, "ESTORNADA", antes, motivoLimpo, configuracao.ler().regras(), hoje);
        lancamentos.inserir(estorno);
        return estornada;
    }

    /** Só o ADMIN, só em aberto (paga: estorne antes). */
    @Transactional
    Consulta cancelar(UUID id, String motivo, UUID usuarioId) {
        Cobranca cobranca = travar(id);
        Map<String, Object> antes = retrato(cobranca);
        cobranca.cancelar(motivo, usuarioId, relogio.instant());
        return gravar(cobranca, "CANCELADA", antes, cobranca.getCancelamento().motivo(),
                configuracao.ler().regras(), hoje());
    }

    /**
     * O caixa de hoje, aberto, travado para leitura até o fim da transação: um fechamento ao
     * mesmo tempo espera este pagamento (ou estorno) terminar e o inclui na conta. Vem antes da
     * trava da cobrança, sempre na mesma ordem.
     */
    private Caixa caixaQueRecebe(LocalDate hoje) {
        Caixa caixa = caixas.travarParaReceber(hoje).orElseThrow(() -> new OperacaoDeCaixaRecusadaException(
                "O caixa de hoje não foi aberto: abra-o, com o troco, antes de receber ou estornar"));
        caixa.conferirQueRecebe();
        return caixa;
    }

    private Cobranca travar(UUID id) {
        return repository.travar(id).orElseThrow(() -> new CobrancaNaoEncontradaException(id));
    }

    private Consulta gravar(Cobranca cobranca, String acao, Map<String, Object> antes, String motivo,
                            RegrasDeAtraso regras, LocalDate hoje) {
        repository.atualizar(cobranca);
        Map<String, Object> depois = retrato(cobranca);
        if (motivo != null) {
            depois.put("motivo", motivo);
        }
        auditoria.registrar(COBRANCA, cobranca.getId(), acao, antes, depois);
        return consulta(repository.buscar(cobranca.getId()).orElseThrow(), regras, hoje);
    }

    private static Consulta consulta(Cobranca cobranca, RegrasDeAtraso regras, LocalDate hoje) {
        Optional<ValorDevido> devido = cobranca.getStatus() == StatusCobranca.PENDENTE
                ? Optional.of(regras.devido(cobranca, hoje)) : Optional.empty();
        return new Consulta(cobranca, devido, hoje);
    }

    private static Map<String, Object> retrato(Cobranca cobranca) {
        Pagamento pagamento = cobranca.getPagamento();
        return Auditoria.campos("status", cobranca.getStatus().name(), "valor", cobranca.getValor(),
                "vencimento", cobranca.getVencimento().toString(),
                "pagoTotal", pagamento == null ? null : pagamento.total(),
                "meioDePagamento", pagamento == null ? null : pagamento.meio().name());
    }

    private LocalDate hoje() {
        Instant agora = relogio.instant();
        return Calendario.hoje(agora);
    }
}
