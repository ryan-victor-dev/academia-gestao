package br.com.academiagestao.matriculas;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import br.com.academiagestao.planos.FormaPagamento;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Um aluno num plano por um período (ADR 0016). Guarda o nome, o valor e a duração do
 * plano no dia em que foi feita: um reajuste posterior não muda o que o aluno contratou (ADR 0013).
 * A situação é calculada pelas datas; só o cancelamento é gravado.
 */
@Entity
@Table(name = "matriculas")
public class Matricula {

    /** "Hoje" é o dia na academia, não em UTC: às 22h de São Paulo, em UTC já é amanhã. */
    static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    static final int DURACAO_MAXIMA_MESES = 60;
    static final int TAMANHO_MAXIMO_MOTIVO = 500;
    static final int TAMANHO_MAXIMO_NOME_DO_PLANO = 80;
    // NUMERIC(10,2): até 8 dígitos antes da vírgula, como em planos.
    static final BigDecimal VALOR_MAXIMO = new BigDecimal("99999999.99");
    static final BigDecimal CEM = new BigDecimal("100");
    static final int DIAS_MINIMOS_TRANCAMENTO = 7;
    static final int DIAS_MAXIMOS_TRANCADOS = 90;

    @Id
    private UUID id;

    @Column(name = "aluno_id", nullable = false, updatable = false)
    private UUID alunoId;

    @Column(name = "plano_id", nullable = false, updatable = false)
    private UUID planoId;

    @Column(name = "plano_nome", nullable = false, updatable = false, length = TAMANHO_MAXIMO_NOME_DO_PLANO)
    private String planoNome;

    @Column(name = "valor_mensal", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal valorMensal;

    @Column(name = "duracao_meses", nullable = false, updatable = false)
    private int duracaoMeses;

    // Regras do plano no dia da matrícula (ADR 0018).
    @Column(nullable = false, updatable = false)
    private boolean fidelidade;

    @Column(name = "percentual_multa", updatable = false, precision = 5, scale = 2)
    private BigDecimal percentualMulta;

    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pagamento", nullable = false, updatable = false, length = 10)
    private FormaPagamento formaPagamento;

    @Column(nullable = false, updatable = false)
    private LocalDate inicio;

    // Avança com os dias trancados (ADR 0018): início + N meses - 1 dia + dias trancados.
    @Column(nullable = false)
    private LocalDate fim;

    @Column(name = "dias_trancados", nullable = false)
    private int diasTrancados;

    // Poucos por matrícula, e a situação depende deles: vêm junto (open-in-view está desligado).
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "matricula_id", nullable = false, updatable = false)
    @OrderBy("inicio")
    private List<Trancamento> trancamentos = new ArrayList<>();

    @Column(name = "criada_em", nullable = false, updatable = false)
    private Instant criadaEm;

    @Column(name = "cancelada_em")
    private Instant canceladaEm;

    @Column(name = "cancelada_por")
    private UUID canceladaPor;

    @Column(name = "motivo_cancelamento", length = TAMANHO_MAXIMO_MOTIVO)
    private String motivoCancelamento;

    // Saída antes do fim de uma matrícula com fidelidade: cancelamento ou troca (ADR 0018).
    @Column(precision = 10, scale = 2)
    private BigDecimal multa;

    // Na matrícula trocada: o dia da troca (ela vale até a véspera) e quem fez.
    @Column(name = "trocada_em")
    private LocalDate trocadaEm;

    @Column(name = "trocada_por")
    private UUID trocadaPor;

    // Na matrícula nova: de qual ela veio.
    @Column(name = "troca_de", updatable = false)
    private UUID trocaDe;

    // Dois cancelamentos simultâneos: só um grava, o outro falha no UPDATE ... WHERE versao = ?.
    @Version
    @Column(nullable = false)
    private long versao;

    // Exigido pelo JPA. Protegido para ninguém criar uma Matricula vazia pelo código.
    protected Matricula() {
    }

    /**
     * @param inicio dia em que começa; {@code null} = hoje. Não pode ser no passado.
     * @param agora  momento do cadastro, de onde sai o "hoje" no fuso da academia.
     */
    static Matricula nova(UUID alunoId, PlanoContratado plano, LocalDate inicio, Instant agora) {
        if (alunoId == null) {
            throw new DadosInvalidosException("aluno é obrigatório");
        }
        if (plano == null || plano.planoId() == null) {
            throw new DadosInvalidosException("plano é obrigatório");
        }
        String nome = plano.nome() == null ? "" : plano.nome().trim();
        if (nome.isEmpty() || nome.length() > TAMANHO_MAXIMO_NOME_DO_PLANO) {
            throw new DadosInvalidosException("nome do plano é obrigatório, com até "
                    + TAMANHO_MAXIMO_NOME_DO_PLANO + " caracteres");
        }
        BigDecimal valor = plano.valorMensal();
        if (valor == null || valor.signum() <= 0) {
            throw new DadosInvalidosException("valor mensal precisa ser maior que zero");
        }
        if (valor.stripTrailingZeros().scale() > 2) {
            throw new DadosInvalidosException("valor mensal pode ter no máximo 2 casas decimais");
        }
        if (valor.compareTo(VALOR_MAXIMO) > 0) {
            throw new DadosInvalidosException("valor mensal pode ser no máximo " + VALOR_MAXIMO);
        }
        int duracao = plano.duracaoMeses();
        if (duracao < 1 || duracao > DURACAO_MAXIMA_MESES) {
            throw new DadosInvalidosException("duração precisa ser de 1 a " + DURACAO_MAXIMA_MESES + " meses");
        }
        BigDecimal percentual = plano.percentualMulta();
        if (plano.fidelidade()) {
            if (percentual == null || percentual.signum() <= 0 || percentual.compareTo(CEM) > 0
                    || percentual.stripTrailingZeros().scale() > 2) {
                throw new DadosInvalidosException("plano com fidelidade precisa de percentual de multa de 0,01 a 100");
            }
        } else if (percentual != null) {
            throw new DadosInvalidosException("percentual de multa só existe em plano com fidelidade");
        }
        if (plano.formaPagamento() == null) {
            throw new DadosInvalidosException("forma de pagamento é obrigatória");
        }
        LocalDate hoje = hoje(agora);
        LocalDate comeco = inicio == null ? hoje : inicio;
        if (comeco.isBefore(hoje)) {
            throw new DadosInvalidosException("início não pode ser no passado");
        }

        Matricula matricula = new Matricula();
        matricula.id = UUID.randomUUID();
        matricula.alunoId = alunoId;
        matricula.planoId = plano.planoId();
        matricula.planoNome = nome;
        matricula.valorMensal = valor.setScale(2);
        matricula.duracaoMeses = duracao;
        matricula.fidelidade = plano.fidelidade();
        matricula.percentualMulta = plano.fidelidade() ? percentual.setScale(2) : null;
        matricula.formaPagamento = plano.formaPagamento();
        matricula.inicio = comeco;
        matricula.fim = fimDoPeriodo(comeco, duracao);
        // O Postgres guarda microssegundos; sem truncar, a resposta e a consulta divergiriam.
        matricula.criadaEm = agora.truncatedTo(ChronoUnit.MICROS);
        return matricula;
    }

    /**
     * Último dia do período, inclusivo: 17/10 + 1 mês vai até 16/11, e a renovação começa
     * em 17/11. Sem o dia no mês final, o plusMonths cai no último dia dele (31/01 + 1 mês
     * = 28/02, fim em 27/02): os períodos continuam encadeados, sem buraco nem sobreposição.
     * O CHECK do banco faz a mesma conta.
     */
    static LocalDate fimDoPeriodo(LocalDate inicio, int duracaoMeses) {
        return inicio.plusMonths(duracaoMeses).minusDays(1);
    }

    public SituacaoMatricula situacao(Instant agora) {
        if (canceladaEm != null) {
            return SituacaoMatricula.CANCELADA;
        }
        if (trocadaEm != null) {
            return SituacaoMatricula.TROCADA;
        }
        LocalDate hoje = hoje(agora);
        if (hoje.isBefore(inicio)) {
            return SituacaoMatricula.AGENDADA;
        }
        if (hoje.isAfter(fim)) {
            return SituacaoMatricula.ENCERRADA;
        }
        return trancamentos.stream().anyMatch(t -> t.paradoEm(hoje))
                ? SituacaoMatricula.TRANCADA : SituacaoMatricula.VIGENTE;
    }

    /**
     * Pausa a matrícula de {@code inicio} (null = hoje) até a véspera de {@code voltaPrevista}
     * e estende o fim pelos dias parados (ADR 0018). Só vigente, um trancamento por vez, de 7 a
     * 90 dias e no máximo 90 somados. Quem confere a renovação agendada é o service, porque
     * envolve outra matrícula.
     */
    void trancar(LocalDate inicio, LocalDate voltaPrevista, String motivo, UUID usuarioId, Instant agora) {
        LocalDate hoje = hoje(agora);
        SituacaoMatricula situacao = situacao(agora);
        if (situacao != SituacaoMatricula.VIGENTE) {
            throw new MatriculaRecusadaException("Trancamento recusado",
                    "Só se tranca matrícula vigente; esta está " + situacao.name().toLowerCase());
        }
        if (trancamentoEmAberto(hoje).isPresent()) {
            throw new MatriculaRecusadaException("Trancamento recusado",
                    "Já existe um trancamento agendado; registre a volta antes de marcar outro");
        }
        if (usuarioId == null) {
            throw new DadosInvalidosException("quem trancou é obrigatório");
        }
        String motivoLimpo = limparMotivo(motivo);
        LocalDate comeco = inicio == null ? hoje : inicio;
        if (comeco.isBefore(hoje) || comeco.isAfter(fim)) {
            throw new DadosInvalidosException("o trancamento começa entre hoje e o fim da matrícula");
        }
        if (voltaPrevista == null) {
            throw new DadosInvalidosException("a volta prevista é obrigatória");
        }
        long dias = ChronoUnit.DAYS.between(comeco, voltaPrevista);
        if (dias < DIAS_MINIMOS_TRANCAMENTO || dias > DIAS_MAXIMOS_TRANCADOS) {
            throw new DadosInvalidosException("o trancamento precisa ter de " + DIAS_MINIMOS_TRANCAMENTO
                    + " a " + DIAS_MAXIMOS_TRANCADOS + " dias");
        }
        if (diasTrancados + dias > DIAS_MAXIMOS_TRANCADOS) {
            throw new MatriculaRecusadaException("Trancamento recusado", "A matrícula já tem %d dias trancados;"
                    .formatted(diasTrancados) + " restam %d dos %d permitidos".formatted(
                    DIAS_MAXIMOS_TRANCADOS - diasTrancados, DIAS_MAXIMOS_TRANCADOS));
        }

        trancamentos.add(new Trancamento(comeco, voltaPrevista, motivoLimpo, usuarioId, agora));
        ajustarDiasTrancados((int) dias);
    }

    /**
     * O aluno voltou antes da data prevista: contam só os dias realmente parados, e o fim é
     * recalculado. Antes de o trancamento começar, ele é desfeito (0 dias).
     */
    void registrarVolta(UUID usuarioId, Instant agora) {
        if (usuarioId == null) {
            throw new DadosInvalidosException("quem registrou a volta é obrigatório");
        }
        LocalDate hoje = hoje(agora);
        Trancamento trancamento = trancamentoEmAberto(hoje).orElseThrow(() -> new MatriculaRecusadaException(
                "Volta recusada", "A matrícula não tem trancamento em andamento nem agendado"));
        ajustarDiasTrancados(-trancamento.voltarEm(hoje, usuarioId));
    }

    private Optional<Trancamento> trancamentoEmAberto(LocalDate hoje) {
        return trancamentos.stream().filter(t -> t.emAbertoEm(hoje)).findFirst();
    }

    private void ajustarDiasTrancados(int diferenca) {
        this.diasTrancados += diferenca;
        this.fim = fim.plusDays(diferenca);
    }

    /**
     * Agendada, vigente ou trancada. Uma vigente termina hoje; uma agendada fica cancelada sem
     * ter começado; um trancamento em aberto termina junto (só contam os dias já parados).
     * Com fidelidade, grava a multa (ver {@link #multaSeEncerrarEm}). Valida tudo antes de
     * alterar: ou o cancelamento inteiro é gravado, ou nada.
     */
    void cancelar(UUID usuarioId, String motivo, Instant agora) {
        SituacaoMatricula situacao = situacao(agora);
        if (situacao != SituacaoMatricula.AGENDADA && situacao != SituacaoMatricula.VIGENTE
                && situacao != SituacaoMatricula.TRANCADA) {
            throw new MatriculaNaoCancelavelException(situacao);
        }
        if (usuarioId == null) {
            throw new DadosInvalidosException("quem cancelou é obrigatório");
        }
        String motivoLimpo = limparMotivo(motivo);

        LocalDate hoje = hoje(agora);
        trancamentoEmAberto(hoje).ifPresent(t -> ajustarDiasTrancados(-t.voltarEm(hoje, usuarioId)));
        this.canceladaEm = agora.truncatedTo(ChronoUnit.MICROS);
        this.canceladaPor = usuarioId;
        this.motivoCancelamento = motivoLimpo;
        this.multa = situacao == SituacaoMatricula.AGENDADA ? null : multaSeEncerrarEm(hoje);
    }

    /**
     * Encerra esta matrícula na véspera de hoje porque o aluno trocou de plano (ADR 0018). Só
     * vigente e sem trancamento em aberto. Com fidelidade, a troca não tem multa só se o novo
     * plano também tiver fidelidade e valor mensal igual ou maior; nos outros casos, aplica a
     * multa desta, como num cancelamento (senão, trocar para um mensal sem fidelidade e
     * cancelar anularia a fidelidade).
     */
    void encerrarPorTroca(Matricula nova, UUID usuarioId, Instant agora) {
        LocalDate hoje = hoje(agora);
        SituacaoMatricula situacao = situacao(agora);
        // Trancada ou com trancamento agendado: os dois têm um trancamento em aberto.
        if (trancamentoEmAberto(hoje).isPresent()) {
            throw new MatriculaRecusadaException("Troca recusada",
                    "A matrícula tem trancamento em aberto; registre a volta antes de trocar de plano");
        }
        if (situacao != SituacaoMatricula.VIGENTE) {
            throw new MatriculaRecusadaException("Troca recusada",
                    "Só se troca o plano de matrícula vigente; esta está " + situacao.name().toLowerCase());
        }
        if (nova.planoId.equals(planoId)) {
            throw new MatriculaRecusadaException("Troca recusada", "A matrícula já é deste plano");
        }
        if (usuarioId == null) {
            throw new DadosInvalidosException("quem trocou é obrigatório");
        }
        boolean mantemOCompromisso = nova.fidelidade && nova.valorMensal.compareTo(valorMensal) >= 0;

        this.multa = mantemOCompromisso ? null : multaSeEncerrarEm(hoje);
        this.trocadaEm = hoje;
        this.trocadaPor = usuarioId;
        nova.trocaDe = this.id;
    }

    private static String limparMotivo(String motivo) {
        String motivoLimpo = motivo == null ? "" : motivo.trim();
        if (motivoLimpo.isEmpty()) {
            throw new DadosInvalidosException("o motivo é obrigatório");
        }
        if (motivoLimpo.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new DadosInvalidosException("motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres");
        }
        return motivoLimpo;
    }

    /**
     * Multa por encerrar antes do fim (ADR 0018): percentual × meses inteiros que faltam ×
     * valor mensal. O mês em curso conta como usado: um anual de 17/10 a 16/10 encerrado em
     * 20/01 tem 8 meses restantes (de 17/02 em diante). Sem fidelidade, não há multa (null).
     */
    BigDecimal multaSeEncerrarEm(LocalDate dia) {
        if (!fidelidade) {
            return null;
        }
        // Conta a partir do dia seguinte: um mês que começa no próprio dia já está em curso.
        long mesesRestantes = ChronoUnit.MONTHS.between(dia.plusDays(1), fim.plusDays(1));
        return valorMensal.multiply(BigDecimal.valueOf(mesesRestantes))
                .multiply(percentualMulta)
                .divide(CEM, 2, RoundingMode.HALF_UP);
    }

    static LocalDate hoje(Instant agora) {
        return LocalDate.ofInstant(agora, FUSO);
    }

    /** Quanto o período inteiro custa: valor mensal × duração, com os valores do dia da matrícula. */
    public BigDecimal getValorTotal() {
        return valorMensal.multiply(BigDecimal.valueOf(duracaoMeses));
    }

    public UUID getId() { return id; }
    public UUID getAlunoId() { return alunoId; }
    public UUID getPlanoId() { return planoId; }
    public String getPlanoNome() { return planoNome; }
    public BigDecimal getValorMensal() { return valorMensal; }
    public int getDuracaoMeses() { return duracaoMeses; }
    public boolean isFidelidade() { return fidelidade; }
    public BigDecimal getPercentualMulta() { return percentualMulta; }
    public FormaPagamento getFormaPagamento() { return formaPagamento; }
    public LocalDate getInicio() { return inicio; }
    public LocalDate getFim() { return fim; }
    public int getDiasTrancados() { return diasTrancados; }
    public List<Trancamento> getTrancamentos() { return Collections.unmodifiableList(trancamentos); }
    public Instant getCriadaEm() { return criadaEm; }
    public Instant getCanceladaEm() { return canceladaEm; }
    public UUID getCanceladaPor() { return canceladaPor; }
    public String getMotivoCancelamento() { return motivoCancelamento; }
    public BigDecimal getMulta() { return multa; }
    public LocalDate getTrocadaEm() { return trocadaEm; }
    public UUID getTrocadaPor() { return trocadaPor; }
    public UUID getTrocaDe() { return trocaDe; }
    public long getVersao() { return versao; }
}
