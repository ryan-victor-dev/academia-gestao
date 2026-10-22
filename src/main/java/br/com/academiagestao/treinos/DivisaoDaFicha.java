package br.com.academiagestao.treinos;

import br.com.academiagestao.compartilhado.Auditoria;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Treino A, B, C... da ficha, com os exercícios em ordem. Nunca é alterada, só trocada. */
@Entity
@Table(name = "ficha_divisoes")
class DivisaoDaFicha {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private int posicao;

    @Column(nullable = false, updatable = false, length = Ficha.TAMANHO_MAXIMO_NOME_DIVISAO)
    private String nome;

    // Vêm sempre junto com a ficha (open-in-view está desligado). BatchSize: uma consulta para
    // os exercícios das divisões carregadas (até 7 por ficha numa página inteira), pelos ids
    // delas, em vez de uma por divisão (e sem o SUBSELECT, que ignorava o limite da página).
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @BatchSize(size = Ficha.MAXIMO_FICHAS_NUMA_CONSULTA * Ficha.MAXIMO_DIVISOES)
    @JoinColumn(name = "divisao_id", nullable = false, updatable = false)
    @OrderBy("posicao")
    private List<ExercicioDaFicha> exercicios;

    // Exigido pelo JPA. Protegido para ninguém criar uma divisão vazia pelo código.
    protected DivisaoDaFicha() {
    }

    // Os dados já chegam conferidos pela Ficha.
    DivisaoDaFicha(int posicao, String nome, List<ExercicioDaFicha> exercicios) {
        this.id = UUID.randomUUID();
        this.posicao = posicao;
        this.nome = nome;
        this.exercicios = new ArrayList<>(exercicios);
    }

    Map<String, Object> retrato() {
        return Auditoria.campos("nome", nome,
                "exercicios", exercicios.stream().map(ExercicioDaFicha::retrato).toList());
    }

    String getNome() { return nome; }
    List<ExercicioDaFicha> getExercicios() { return List.copyOf(exercicios); }
}
