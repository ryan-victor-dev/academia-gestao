package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma cobrança de uma matrícula (ADR 0024): mensalidade, à vista ou multa. O estado só muda
 * por métodos com nome de negócio, que conferem a transição (ADR 0004); sem setters.
 */
public class Cobranca {

    public static final int TAMANHO_MAXIMO_MOTIVO = 500;

    private final UUID id;
    private final UUID matriculaId;
    private final UUID alunoId;
    private final TipoCobranca tipo;
    private final int parcela;
    private final BigDecimal valor;
    private LocalDate vencimento;
    private StatusCobranca status;
    private Pagamento pagamento;
    private Cancelamento cancelamento;
    private final long versao;

    private Cobranca(UUID id, UUID matriculaId, UUID alunoId, TipoCobranca tipo, int parcela, BigDecimal valor,
                     LocalDate vencimento, StatusCobranca status, Pagamento pagamento, Cancelamento cancelamento,
                     long versao) {
        this.id = id;
        this.matriculaId = matriculaId;
        this.alunoId = alunoId;
        this.tipo = tipo;
        this.parcela = parcela;
        this.valor = valor;
        this.vencimento = vencimento;
        this.status = status;
        this.pagamento = pagamento;
        this.cancelamento = cancelamento;
        this.versao = versao;
    }

    /** Uma parcela prevista do cronograma vira cobrança em aberto. */
    static Cobranca nova(UUID matriculaId, UUID alunoId, ParcelaPrevista prevista) {
        Objects.requireNonNull(matriculaId, "matrícula é obrigatória");
        Objects.requireNonNull(alunoId, "aluno é obrigatório");
        Objects.requireNonNull(prevista.vencimento(), "vencimento é obrigatório");
        if (prevista.valor() == null || prevista.valor().signum() <= 0) {
            throw new IllegalArgumentException("valor da cobrança deve ser positivo");
        }
        return new Cobranca(UUID.randomUUID(), matriculaId, alunoId, prevista.tipo(), prevista.parcela(),
                prevista.valor().setScale(2), prevista.vencimento(), StatusCobranca.PENDENTE, null, null, 0);
    }

    /** Remonta uma cobrança lida do banco. */
    public static Cobranca restaurar(UUID id, UUID matriculaId, UUID alunoId, TipoCobranca tipo, int parcela,
                                     BigDecimal valor, LocalDate vencimento, StatusCobranca status,
                                     Pagamento pagamento, Cancelamento cancelamento, long versao) {
        return new Cobranca(id, matriculaId, alunoId, tipo, parcela, valor, vencimento, status, pagamento,
                cancelamento, versao);
    }

    /**
     * Registra o pagamento do valor exato devido hoje (ADR 0024). Outro valor é recusado com o
     * devido: sem parcial, sem desconto, e sem receber menos porque a tela era de ontem.
     */
    public void pagar(RegrasDeAtraso regras, LocalDate hoje, BigDecimal informado, MeioDePagamento meio,
                      UUID por, Instant agora) {
        conferirTransicao(StatusCobranca.PAGA);
        if (meio == null) {
            throw new CobrancaInvalidaException("meio de pagamento é obrigatório");
        }
        Objects.requireNonNull(por, "quem registrou é obrigatório");
        ValorDevido devido = regras.devido(this, hoje);
        if (informado == null || informado.compareTo(devido.total()) != 0) {
            throw new PagamentoRecusadoException(devido);
        }
        this.status = StatusCobranca.PAGA;
        this.pagamento = new Pagamento(devido.acrescimo(), devido.juros(), devido.total(), meio,
                agora.truncatedTo(ChronoUnit.MICROS), por);
    }

    /** Desfaz o pagamento: a cobrança volta a ficar em aberto e pode atrasar (ADR 0024). */
    public void estornar() {
        conferirTransicao(StatusCobranca.PENDENTE);
        this.status = StatusCobranca.PENDENTE;
        this.pagamento = null;
    }

    /**
     * Só em aberto: paga, estorne antes. {@code por} nulo = o sistema (matrícula encerrada).
     */
    public void cancelar(String motivo, UUID por, Instant agora) {
        conferirTransicao(StatusCobranca.CANCELADA);
        String motivoLimpo = motivo == null ? "" : motivo.trim();
        if (motivoLimpo.isEmpty() || motivoLimpo.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new CobrancaInvalidaException("o motivo é obrigatório, com até " + TAMANHO_MAXIMO_MOTIVO
                    + " caracteres");
        }
        this.status = StatusCobranca.CANCELADA;
        this.cancelamento = new Cancelamento(agora.truncatedTo(ChronoUnit.MICROS), por, motivoLimpo);
    }

    /** O cronograma mudou (trancamento, volta): a cobrança vence em outro dia. */
    void reprogramar(LocalDate novoVencimento) {
        if (status == StatusCobranca.CANCELADA) {
            throw new TransicaoInvalidaException(status, status);
        }
        this.vencimento = Objects.requireNonNull(novoVencimento);
    }

    public boolean atrasada(LocalDate hoje) {
        return RegrasDeAtraso.diasDeAtraso(this, hoje) > 0;
    }

    private void conferirTransicao(StatusCobranca destino) {
        if (!status.podeIrPara(destino)) {
            throw new TransicaoInvalidaException(status, destino);
        }
    }

    public UUID getId() { return id; }
    public UUID getMatriculaId() { return matriculaId; }
    public UUID getAlunoId() { return alunoId; }
    public TipoCobranca getTipo() { return tipo; }
    public int getParcela() { return parcela; }
    public BigDecimal getValor() { return valor; }
    public LocalDate getVencimento() { return vencimento; }
    public StatusCobranca getStatus() { return status; }
    public Pagamento getPagamento() { return pagamento; }
    public Cancelamento getCancelamento() { return cancelamento; }
    public long getVersao() { return versao; }
}
