package br.com.academiagestao.treinos;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** O que o instrutor informa ao montar ou corrigir uma ficha. Conferido pela {@link Ficha}. */
record DadosFicha(LocalDate revisaoEm, List<Divisao> divisoes) {

    record Divisao(String nome, List<Item> exercicios) {
    }

    /** Um exercício do catálogo na divisão. Repetições e carga são texto: "8 a 12", "20 kg". */
    record Item(UUID exercicioId, Integer series, String repeticoes, String carga, Integer descansoSegundos,
                String observacao) {
    }

    /** Os exercícios do catálogo citados, para buscá-los de uma vez (ignora o que vier nulo). */
    Set<UUID> exercicioIds() {
        if (divisoes == null) {
            return Set.of();
        }
        return divisoes.stream()
                .filter(Objects::nonNull)
                .filter(d -> d.exercicios() != null)
                .flatMap(d -> d.exercicios().stream())
                .filter(Objects::nonNull)
                .map(Item::exercicioId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
