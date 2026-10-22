package br.com.academiagestao.planos;

import br.com.academiagestao.compartilhado.CondicoesDoPlano;
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

    @Id
    private UUID id;

    @Column(nullable = false, length = CondicoesDoPlano.TAMANHO_MAXIMO_NOME)
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
        CondicoesDoPlano condicoes = CondicoesDoPlano.conferir(dados.nome(), dados.valorMensal(),
                dados.duracaoMeses(), dados.fidelidade(), dados.percentualMulta());

        this.nome = condicoes.nome();
        this.valorMensal = condicoes.valorMensal();
        this.duracaoMeses = condicoes.duracaoMeses();
        this.fidelidade = condicoes.fidelidade();
        this.percentualMulta = condicoes.percentualMulta();
        this.formaPagamento = dados.formaPagamento() == null ? FormaPagamento.MENSAL : dados.formaPagamento();
    }

    static String limparNome(String nome) {
        return CondicoesDoPlano.limparNome(nome);
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
