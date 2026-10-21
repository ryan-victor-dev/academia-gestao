package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.Lancamento;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
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
 * lançamentos (ADR 0025).
 */
@Service
class CobrancaService {

    private final CobrancaRepository repository;
    private final LancamentoRepository lancamentos;
    private final ConfiguracaoDaCobrancaRepository configuracao;
    private final Auditoria auditoria;
    private final Clock relogio;

    CobrancaService(CobrancaRepository repository, LancamentoRepository lancamentos,
                    ConfiguracaoDaCobrancaRepository configuracao, Auditoria auditoria, Clock relogio) {
        this.repository = repository;
        this.lancamentos = lancamentos;
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

    /** Só o valor exato devido hoje; outro valor é recusado com o devido (ADR 0024). */
    @Transactional
    Consulta pagar(UUID id, BigDecimal valor, MeioDePagamento meio, UUID usuarioId) {
        Cobranca cobranca = travar(id);
        Map<String, Object> antes = retrato(cobranca);
        RegrasDeAtraso regras = configuracao.ler().regras();
        LocalDate hoje = hoje();
        cobranca.pagar(regras, hoje, valor, meio, usuarioId, relogio.instant());
        Consulta paga = gravar(cobranca, "PAGA", antes, null, regras, hoje);
        lancamentos.inserir(Lancamento.doPagamento(cobranca));
        return paga;
    }

    /**
     * Só o ADMIN: a cobrança volta a ficar em aberto, e o pagamento continua no registro, com
     * um lançamento de estorno ao lado (ADR 0025).
     */
    @Transactional
    Consulta estornar(UUID id, String motivo, UUID usuarioId) {
        String motivoLimpo = Cobranca.motivoValido(motivo);
        Cobranca cobranca = travar(id);
        Map<String, Object> antes = retrato(cobranca);
        LocalDate hoje = hoje();
        cobranca.estornar(hoje);
        Lancamento estorno = lancamentos.pagamentoEmVigor(id)
                .orElseThrow(() -> new IllegalStateException("cobrança paga sem o lançamento do pagamento: " + id))
                .estornar(motivoLimpo, usuarioId, relogio.instant());
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
