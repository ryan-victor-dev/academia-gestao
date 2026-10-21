package br.com.academiagestao.treinos;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Exercício do catálogo da academia, mantido pelos instrutores (ADR 0027). A ficha copia o
 * nome dele: renomear aqui não muda o histórico. Não se apaga: é inativado.
 */
@Entity
@Table(name = "exercicios")
class Exercicio {

    static final int TAMANHO_MAXIMO_NOME = 80;
    static final int TAMANHO_MAXIMO_OBSERVACAO = 500;

    @Id
    private UUID id;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(name = "grupo_muscular", nullable = false, length = 20)
    private GrupoMuscular grupoMuscular;

    // Como executar: postura, amplitude, cuidados.
    @Column(length = TAMANHO_MAXIMO_OBSERVACAO)
    private String observacao;

    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    @Version
    @Column(nullable = false)
    private long versao;

    // Exigido pelo JPA. Protegido para ninguém criar um Exercicio vazio pelo código.
    protected Exercicio() {
    }

    static Exercicio novo(DadosExercicio dados, Instant agora) {
        Exercicio exercicio = new Exercicio();
        exercicio.aplicar(dados);
        exercicio.id = UUID.randomUUID();
        exercicio.ativo = true;
        exercicio.criadoEm = agora.truncatedTo(ChronoUnit.MICROS);
        return exercicio;
    }

    void atualizar(DadosExercicio dados) {
        aplicar(dados);
    }

    /** Sai das fichas novas; as que já o usam continuam com ele. */
    void inativar() {
        this.ativo = false;
    }

    void reativar() {
        this.ativo = true;
    }

    // Valida tudo antes de alterar qualquer campo: ou o exercício inteiro muda, ou nada muda.
    private void aplicar(DadosExercicio dados) {
        if (dados == null) {
            throw new DadosInvalidosException("dados do exercício são obrigatórios");
        }
        String nomeLimpo = limpar(dados.nome());
        if (nomeLimpo.isEmpty()) {
            throw new DadosInvalidosException("nome é obrigatório");
        }
        if (nomeLimpo.length() > TAMANHO_MAXIMO_NOME) {
            throw new DadosInvalidosException("nome pode ter no máximo " + TAMANHO_MAXIMO_NOME + " caracteres");
        }
        if (dados.grupoMuscular() == null) {
            throw new DadosInvalidosException("grupo muscular é obrigatório");
        }
        String observacaoLimpa = dados.observacao() == null ? "" : dados.observacao().strip();
        if (observacaoLimpa.length() > TAMANHO_MAXIMO_OBSERVACAO) {
            throw new DadosInvalidosException("observação pode ter no máximo " + TAMANHO_MAXIMO_OBSERVACAO
                    + " caracteres");
        }

        this.nome = nomeLimpo;
        this.grupoMuscular = dados.grupoMuscular();
        this.observacao = observacaoLimpa.isEmpty() ? null : observacaoLimpa;
    }

    /** Sem espaços nas pontas nem repetidos: "  Supino   reto " vira "Supino reto". */
    static String limpar(String nome) {
        return nome == null ? "" : nome.strip().replaceAll("\\s+", " ");
    }

    UUID getId() { return id; }
    String getNome() { return nome; }
    GrupoMuscular getGrupoMuscular() { return grupoMuscular; }
    String getObservacao() { return observacao; }
    boolean isAtivo() { return ativo; }
    Instant getCriadoEm() { return criadoEm; }
    long getVersao() { return versao; }
}
