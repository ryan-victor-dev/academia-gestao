package br.com.academiagestao.treinos;

import br.com.academiagestao.compartilhado.Auditoria;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Map;
import java.util.UUID;

/**
 * Um exercício numa divisão da ficha. Nunca é alterado: a correção da ficha troca as
 * divisões inteiras. O nome é copiado do catálogo (ADR 0027).
 */
@Entity
@Table(name = "ficha_exercicios")
class ExercicioDaFicha {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private int posicao;

    @Column(name = "exercicio_id", nullable = false, updatable = false)
    private UUID exercicioId;

    @Column(nullable = false, updatable = false, length = Exercicio.TAMANHO_MAXIMO_NOME)
    private String nome;

    @Column(nullable = false, updatable = false)
    private int series;

    @Column(nullable = false, updatable = false, length = Ficha.TAMANHO_MAXIMO_REPETICOES)
    private String repeticoes;

    @Column(updatable = false, length = Ficha.TAMANHO_MAXIMO_CARGA)
    private String carga;

    @Column(name = "descanso_segundos", updatable = false)
    private Integer descansoSegundos;

    // A coluna continua com 500: as fichas de antes do limite de 200 (ADR 0046, item 15) seguem valendo.
    @Column(updatable = false, length = 500)
    private String observacao;

    // Exigido pelo JPA. Protegido para ninguém criar um item vazio pelo código.
    protected ExercicioDaFicha() {
    }

    // Os dados já chegam conferidos pela Ficha.
    ExercicioDaFicha(int posicao, UUID exercicioId, String nome, int series, String repeticoes, String carga,
                     Integer descansoSegundos, String observacao) {
        this.id = UUID.randomUUID();
        this.posicao = posicao;
        this.exercicioId = exercicioId;
        this.nome = nome;
        this.series = series;
        this.repeticoes = repeticoes;
        this.carga = carga;
        this.descansoSegundos = descansoSegundos;
        this.observacao = observacao;
    }

    Map<String, Object> retrato() {
        return Auditoria.campos("exercicioId", exercicioId, "nome", nome, "series", series,
                "repeticoes", repeticoes, "carga", carga, "descansoSegundos", descansoSegundos,
                "observacao", observacao);
    }

    UUID getExercicioId() { return exercicioId; }
    String getNome() { return nome; }
    int getSeries() { return series; }
    String getRepeticoes() { return repeticoes; }
    String getCarga() { return carga; }
    Integer getDescansoSegundos() { return descansoSegundos; }
    String getObservacao() { return observacao; }
}
