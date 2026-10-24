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
    static final String MOTIVO_FORA_DO_CRONOGRAMA =
            "Estornada depois de sair do cronograma (matrícula encerrada): não é mais devida";

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
    private LocalDate estornadaNoDia;
    private boolean foraDoCronograma;
    private final long versao;

    private Cobranca(UUID id, UUID matriculaId, UUID alunoId, TipoCobranca tipo, int parcela, BigDecimal valor,
                     LocalDate vencimento, StatusCobranca status, Pagamento pagamento, Cancelamento cancelamento,
                     LocalDate estornadaNoDia, boolean foraDoCronograma, long versao) {
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
        this.estornadaNoDia = estornadaNoDia;
        this.foraDoCronograma = foraDoCronograma;
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
                prevista.valor().setScale(2), prevista.vencimento(), StatusCobranca.PENDENTE, null, null, null, false, 0);
    }

    /** Remonta uma cobrança lida do banco. */
    public static Cobranca restaurar(UUID id, UUID matriculaId, UUID alunoId, TipoCobranca tipo, int parcela,
                                     BigDecimal valor, LocalDate vencimento, StatusCobranca status,
                                     Pagamento pagamento, Cancelamento cancelamento, LocalDate estornadaNoDia,
                                     boolean foraDoCronograma, long versao) {
        return new Cobranca(id, matriculaId, alunoId, tipo, parcela, valor, vencimento, status, pagamento,
                cancelamento, estornadaNoDia, foraDoCronograma, versao);
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
        if (meio.online()) {
            throw new CobrancaInvalidaException("Pix online e boleto só se recebem pelo app (empresa de pagamentos)");
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

    /**
     * Registra um pagamento online (ADR 0034) com o valor combinado no dia em que o aluno gerou
     * o Pix ou o boleto, e não com o de hoje: o boleto tem valor fixo, e o aluno pagou o que viu.
     * Quem chama confere antes se a cobrança ainda está em aberto e se o recebido é o combinado;
     * aqui, as duas coisas são recusadas de novo.
     */
    public void pagarOnline(ValorDevido combinado, BigDecimal recebido, MeioDePagamento meio, UUID por,
                            Instant agora) {
        conferirTransicao(StatusCobranca.PAGA);
        if (meio == null || !meio.online()) {
            throw new CobrancaInvalidaException("o pagamento online é por Pix online ou boleto");
        }
        Objects.requireNonNull(por, "quem gerou o pagamento é obrigatório");
        if (combinado.valor().compareTo(valor) != 0 || recebido == null || recebido.compareTo(combinado.total()) != 0) {
            throw new PagamentoRecusadoException(combinado);
        }
        this.status = StatusCobranca.PAGA;
        this.pagamento = new Pagamento(combinado.acrescimo(), combinado.juros(), combinado.total(), meio,
                agora.truncatedTo(ChronoUnit.MICROS), por);
    }

    /**
     * Desfaz o pagamento: a cobrança volta a ficar em aberto e pode atrasar (ADR 0024), mas o
     * atraso só conta do dia do estorno em diante (ver {@link #getPrazo}). Se ela já saiu do
     * cronograma (paga adiantada de uma matrícula cancelada), não é mais devida: fica cancelada,
     * por quem estornou (decisão do dono, ADR 0026). A paga adiantada de uma matrícula trocada
     * não sai: virou crédito na nova, então o estorno a faz voltar a dever. O pagamento continua no registro, com o
     * estorno ao lado ({@link Lancamento}, ADR 0025).
     */
    public void estornar(LocalDate hoje, UUID por, Instant agora) {
        conferirTransicao(StatusCobranca.PENDENTE);
        Objects.requireNonNull(hoje, "o dia do estorno é obrigatório");
        Objects.requireNonNull(por, "quem estornou é obrigatório");
        this.status = StatusCobranca.PENDENTE;
        this.pagamento = null;
        this.estornadaNoDia = hoje;
        if (foraDoCronograma) {
            cancelar(MOTIVO_FORA_DO_CRONOGRAMA, por, agora);
        }
    }

    /**
     * Só em aberto: paga, estorne antes. {@code por} nulo = o sistema (matrícula encerrada).
     */
    public void cancelar(String motivo, UUID por, Instant agora) {
        conferirTransicao(StatusCobranca.CANCELADA);
        String motivoLimpo = motivoValido(motivo);
        this.status = StatusCobranca.CANCELADA;
        this.cancelamento = new Cancelamento(agora.truncatedTo(ChronoUnit.MICROS), por, motivoLimpo);
    }

    /** O motivo de um cancelamento ou estorno, sem os espaços das pontas: obrigatório e com até 500 caracteres. */
    public static String motivoValido(String motivo) {
        String limpo = motivo == null ? "" : motivo.trim();
        if (limpo.isEmpty() || limpo.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new CobrancaInvalidaException("o motivo é obrigatório, com até " + TAMANHO_MAXIMO_MOTIVO
                    + " caracteres");
        }
        return limpo;
    }

    /**
     * Paga, mas a matrícula foi cancelada antes do período dela: continua paga (ADR 0024), e um
     * estorno a cancela em vez de fazê-la voltar a dever.
     */
    void sairDoCronograma() {
        if (status != StatusCobranca.PAGA) {
            throw new TransicaoInvalidaException(status, status);
        }
        this.foraDoCronograma = true;
    }

    /** O cronograma mudou (trancamento, volta): a cobrança vence em outro dia. */
    void reprogramar(LocalDate novoVencimento) {
        if (status == StatusCobranca.CANCELADA) {
            throw new TransicaoInvalidaException(status, status);
        }
        this.vencimento = Objects.requireNonNull(novoVencimento);
    }

    /**
     * Até quando se paga sem atraso: o vencimento ou, se depois dele, o dia do último estorno.
     * Até o estorno, a academia dava a cobrança por paga: acréscimo, juros e bloqueio contam
     * só dali em diante (decisão do dono, ADR 0026). O banco faz a mesma conta (coluna {@code prazo}).
     */
    public LocalDate getPrazo() {
        return estornadaNoDia != null && estornadaNoDia.isAfter(vencimento) ? estornadaNoDia : vencimento;
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
    public LocalDate getEstornadaNoDia() { return estornadaNoDia; }
    public boolean isForaDoCronograma() { return foraDoCronograma; }
    public long getVersao() { return versao; }
}
