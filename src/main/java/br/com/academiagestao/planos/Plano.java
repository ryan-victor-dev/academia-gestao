package br.com.academiagestao.planos;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Plano oferecido pela academia. O valor pode ser reajustado: quem já está matriculado
 * não é afetado, porque cada matrícula guarda o valor do dia em que foi feita (etapa 5).
 */
@Entity
@Table(name = "planos")
public class Plano {

    static final int TAMANHO_MAXIMO_NOME = 80;
    static final int DURACAO_MAXIMA_MESES = 60;
    // Teto decidido pelo dono (2026-10-20): o à vista de 60 meses (6 milhões) cabe na cobrança.
    static final BigDecimal VALOR_MAXIMO = new BigDecimal("100000.00");
    static final BigDecimal PERCENTUAL_MAXIMO_MULTA = new BigDecimal("100");

    @Id
    private UUID id;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    @Column(name = "valor_mensal", nullable = false, precision = 10, scale = 2)
    private BigDecimal valorMensal;

    @Column(name = "duracao_meses", nullable = false)
    private int duracaoMeses;

    // Com fidelidade, quem cancela antes do fim paga o percentual sobre o saldo (ADR 0018).
    @Column(nullable = false)
    private boolean fidelidade;

    @Column(name = "percentual_multa", precision = 5, scale = 2)
    private BigDecimal percentualMulta;

    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pagamento", nullable = false, length = 10)
    private FormaPagamento formaPagamento;

    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    @Version
    @Column(nullable = false)
    private long versao;

    // Exigido pelo JPA. Protegido para ninguém criar um Plano vazio pelo código.
    protected Plano() {
    }

    public static Plano novo(DadosPlano dados) {
        Plano plano = new Plano();
        plano.id = UUID.randomUUID();
        plano.ativo = true;
        plano.criadoEm = Instant.now().truncatedTo(ChronoUnit.MICROS);
        plano.aplicar(dados);
        return plano;
    }

    public void atualizar(DadosPlano dados) {
        aplicar(dados);
    }

    /** Deixa de ser oferecido. Quem já está matriculado nele continua (etapa 5). */
    public void inativar() {
        this.ativo = false;
    }

    public void reativar() {
        this.ativo = true;
    }

    /** Quanto o plano custa no total: valor mensal × duração. */
    public BigDecimal getValorTotal() {
        return valorMensal.multiply(BigDecimal.valueOf(duracaoMeses));
    }

    // Valida tudo antes de alterar qualquer campo: ou o plano inteiro muda, ou nada muda.
    private void aplicar(DadosPlano dados) {
        if (dados == null) {
            throw new DadosInvalidosException("dados do plano são obrigatórios");
        }
        String nomeLimpo = limparNome(dados.nome());
        if (nomeLimpo.isEmpty()) {
            throw new DadosInvalidosException("nome é obrigatório");
        }
        if (nomeLimpo.length() > TAMANHO_MAXIMO_NOME) {
            throw new DadosInvalidosException("nome pode ter no máximo " + TAMANHO_MAXIMO_NOME + " caracteres");
        }
        BigDecimal valor = dados.valorMensal();
        if (valor == null || valor.signum() <= 0) {
            throw new DadosInvalidosException("valor mensal precisa ser maior que zero");
        }
        // 129.90 e 129.9 são o mesmo valor; 129.999 não existe em reais.
        if (valor.stripTrailingZeros().scale() > 2) {
            throw new DadosInvalidosException("valor mensal pode ter no máximo 2 casas decimais");
        }
        if (valor.compareTo(VALOR_MAXIMO) > 0) {
            throw new DadosInvalidosException("valor mensal pode ser no máximo " + VALOR_MAXIMO);
        }
        Integer duracao = dados.duracaoMeses();
        if (duracao == null || duracao < 1 || duracao > DURACAO_MAXIMA_MESES) {
            throw new DadosInvalidosException("duração precisa ser de 1 a " + DURACAO_MAXIMA_MESES + " meses");
        }
        boolean comFidelidade = Boolean.TRUE.equals(dados.fidelidade());
        BigDecimal multa = dados.percentualMulta();
        if (comFidelidade) {
            if (multa == null || multa.signum() <= 0 || multa.compareTo(PERCENTUAL_MAXIMO_MULTA) > 0) {
                throw new DadosInvalidosException("plano com fidelidade precisa de percentual de multa de 0,01 a 100");
            }
            if (multa.stripTrailingZeros().scale() > 2) {
                throw new DadosInvalidosException("percentual de multa pode ter no máximo 2 casas decimais");
            }
        } else if (multa != null) {
            throw new DadosInvalidosException("percentual de multa só existe em plano com fidelidade");
        }

        this.nome = nomeLimpo;
        this.valorMensal = valor.setScale(2);
        this.duracaoMeses = duracao;
        this.fidelidade = comFidelidade;
        this.percentualMulta = comFidelidade ? multa.setScale(2) : null;
        this.formaPagamento = dados.formaPagamento() == null ? FormaPagamento.MENSAL : dados.formaPagamento();
    }

    /** Sem espaços nas pontas nem repetidos: "  Plano   Anual " vira "Plano Anual". */
    static String limparNome(String nome) {
        return nome == null ? "" : nome.trim().replaceAll("\\s+", " ");
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public BigDecimal getValorMensal() { return valorMensal; }
    public int getDuracaoMeses() { return duracaoMeses; }
    public boolean isFidelidade() { return fidelidade; }
    public BigDecimal getPercentualMulta() { return percentualMulta; }
    public FormaPagamento getFormaPagamento() { return formaPagamento; }
    public boolean isAtivo() { return ativo; }
    public Instant getCriadoEm() { return criadoEm; }
    public long getVersao() { return versao; }
}
