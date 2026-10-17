package br.com.academiagestao.matriculas;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

    @Column(nullable = false, updatable = false)
    private LocalDate inicio;

    @Column(nullable = false, updatable = false)
    private LocalDate fim;

    @Column(name = "criada_em", nullable = false, updatable = false)
    private Instant criadaEm;

    @Column(name = "cancelada_em")
    private Instant canceladaEm;

    @Column(name = "cancelada_por")
    private UUID canceladaPor;

    @Column(name = "motivo_cancelamento", length = TAMANHO_MAXIMO_MOTIVO)
    private String motivoCancelamento;

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
        LocalDate hoje = hoje(agora);
        if (hoje.isBefore(inicio)) {
            return SituacaoMatricula.AGENDADA;
        }
        return hoje.isAfter(fim) ? SituacaoMatricula.ENCERRADA : SituacaoMatricula.VIGENTE;
    }

    /**
     * Só agendada ou vigente. Uma vigente termina hoje; uma agendada fica cancelada sem ter
     * começado. Valida tudo antes de alterar: ou o cancelamento inteiro é gravado, ou nada.
     */
    void cancelar(UUID usuarioId, String motivo, Instant agora) {
        SituacaoMatricula situacao = situacao(agora);
        if (situacao != SituacaoMatricula.AGENDADA && situacao != SituacaoMatricula.VIGENTE) {
            throw new MatriculaNaoCancelavelException(situacao);
        }
        if (usuarioId == null) {
            throw new DadosInvalidosException("quem cancelou é obrigatório");
        }
        String motivoLimpo = motivo == null ? "" : motivo.trim();
        if (motivoLimpo.isEmpty()) {
            throw new DadosInvalidosException("motivo do cancelamento é obrigatório");
        }
        if (motivoLimpo.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new DadosInvalidosException("motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres");
        }

        this.canceladaEm = agora.truncatedTo(ChronoUnit.MICROS);
        this.canceladaPor = usuarioId;
        this.motivoCancelamento = motivoLimpo;
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
    public LocalDate getInicio() { return inicio; }
    public LocalDate getFim() { return fim; }
    public Instant getCriadaEm() { return criadaEm; }
    public Instant getCanceladaEm() { return canceladaEm; }
    public UUID getCanceladaPor() { return canceladaPor; }
    public String getMotivoCancelamento() { return motivoCancelamento; }
    public long getVersao() { return versao; }
}
