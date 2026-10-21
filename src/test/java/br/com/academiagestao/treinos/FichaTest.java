package br.com.academiagestao.treinos;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras da ficha de treino (ADR 0027), sem banco. */
class FichaTest {

    // 21/10 às 02:00 UTC ainda é 20/10 na academia (America/Sao_Paulo).
    private static final Instant MONTAGEM = Instant.parse("2026-10-21T02:00:00Z");
    private static final UUID SUPINO = UUID.randomUUID();
    private static final UUID REMADA = UUID.randomUUID();
    private static final Map<UUID, String> NOMES = Map.of(SUPINO, "Supino reto", REMADA, "Remada curvada");

    private static DadosFicha.Item item(UUID exercicio) {
        return new DadosFicha.Item(exercicio, 3, "10", null, null, null);
    }

    private static DadosFicha umaDivisao(DadosFicha.Item... itens) {
        return new DadosFicha(null, List.of(new DadosFicha.Divisao("A", Arrays.asList(itens))));
    }

    private static Ficha montar(DadosFicha dados) {
        return Ficha.montar(UUID.randomUUID(), dados, NOMES, UUID.randomUUID(), MONTAGEM);
    }

    @Test
    void montaComNomesDoCatalogoEmOrdemELimpaOsTextos() {
        UUID aluno = UUID.randomUUID();
        UUID instrutor = UUID.randomUUID();
        DadosFicha dados = new DadosFicha(LocalDate.of(2026, 11, 20), List.of(
                new DadosFicha.Divisao("  A —  peito ", List.of(
                        new DadosFicha.Item(SUPINO, 4, " 8 a  12 ", " 20 kg ", 90, "  Pegada fechada "),
                        new DadosFicha.Item(REMADA, 3, "10", "  ", null, " "))),
                new DadosFicha.Divisao("B", List.of(item(SUPINO)))));

        Ficha ficha = Ficha.montar(aluno, dados, NOMES, instrutor, MONTAGEM);

        assertThat(ficha.getAlunoId()).isEqualTo(aluno);
        assertThat(ficha.getMontadaPor()).isEqualTo(instrutor);
        assertThat(ficha.getMontadaEm()).isEqualTo(MONTAGEM);
        assertThat(ficha.isAtual()).isTrue();
        assertThat(ficha.getRevisaoEm()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(ficha.getDivisoes()).extracting(DivisaoDaFicha::getNome).containsExactly("A — peito", "B");
        List<ExercicioDaFicha> a = ficha.getDivisoes().getFirst().getExercicios();
        assertThat(a).extracting(ExercicioDaFicha::getNome).containsExactly("Supino reto", "Remada curvada");
        assertThat(a.getFirst().getSeries()).isEqualTo(4);
        assertThat(a.getFirst().getRepeticoes()).isEqualTo("8 a 12");
        assertThat(a.getFirst().getCarga()).isEqualTo("20 kg");
        assertThat(a.getFirst().getDescansoSegundos()).isEqualTo(90);
        assertThat(a.getFirst().getObservacao()).isEqualTo("Pegada fechada");
        // Em branco vira vazio (nulo), não texto em branco.
        assertThat(a.get(1).getCarga()).isNull();
        assertThat(a.get(1).getObservacao()).isNull();
        assertThat(a.get(1).getDescansoSegundos()).isNull();
    }

    @Test
    void mesmoExercicioPodeRepetir() {
        Ficha ficha = montar(umaDivisao(item(SUPINO), item(SUPINO)));

        assertThat(ficha.getDivisoes().getFirst().getExercicios()).hasSize(2);
    }

    static Stream<UnaryOperator<DadosFicha.Item>> itensInvalidos() {
        return Stream.of(
                i -> new DadosFicha.Item(null, 3, "10", null, null, null),
                i -> new DadosFicha.Item(UUID.randomUUID(), 3, "10", null, null, null), // fora do catálogo
                i -> new DadosFicha.Item(SUPINO, null, "10", null, null, null),
                i -> new DadosFicha.Item(SUPINO, 0, "10", null, null, null),
                i -> new DadosFicha.Item(SUPINO, 21, "10", null, null, null),
                i -> new DadosFicha.Item(SUPINO, 3, " ", null, null, null),
                i -> new DadosFicha.Item(SUPINO, 3, null, null, null, null),
                i -> new DadosFicha.Item(SUPINO, 3, "x".repeat(21), null, null, null),
                i -> new DadosFicha.Item(SUPINO, 3, "10", "x".repeat(31), null, null),
                i -> new DadosFicha.Item(SUPINO, 3, "10", null, -1, null),
                i -> new DadosFicha.Item(SUPINO, 3, "10", null, 601, null),
                i -> new DadosFicha.Item(SUPINO, 3, "10", null, null, "x".repeat(501)),
                i -> null);
    }

    @ParameterizedTest
    @MethodSource("itensInvalidos")
    void itemInvalidoERecusado(UnaryOperator<DadosFicha.Item> invalido) {
        assertThatThrownBy(() -> montar(umaDivisao(item(SUPINO), invalido.apply(null))))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessageStartingWith("divisão 1, exercício 2:");
    }

    @Test
    void limitesAceitos() {
        DadosFicha.Item noLimite = new DadosFicha.Item(SUPINO, 20, "x".repeat(20), "x".repeat(30), 600,
                "x".repeat(500));
        DadosFicha.Item noMinimo = new DadosFicha.Item(SUPINO, 1, "1", null, 0, null);
        List<DadosFicha.Item> vinte = Collections.nCopies(20, noLimite);
        List<DadosFicha.Divisao> sete = Stream.of("A", "B", "C", "D", "E", "F", "x".repeat(40))
                .map(nome -> new DadosFicha.Divisao(nome, vinte)).toList();

        assertThat(montar(new DadosFicha(null, sete)).getDivisoes()).hasSize(7);
        assertThat(montar(umaDivisao(noMinimo)).getDivisoes().getFirst().getExercicios().getFirst()
                .getDescansoSegundos()).isZero();
    }

    @Test
    void divisoesInvalidasSaoRecusadas() {
        List<DadosFicha.Item> um = List.of(item(SUPINO));
        assertThatThrownBy(() -> montar(null)).isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> montar(new DadosFicha(null, null)))
                .hasMessage("a ficha precisa de pelo menos uma divisão");
        assertThatThrownBy(() -> montar(new DadosFicha(null, List.of())))
                .hasMessage("a ficha precisa de pelo menos uma divisão");
        List<DadosFicha.Divisao> oito = Stream.of("A", "B", "C", "D", "E", "F", "G", "H")
                .map(nome -> new DadosFicha.Divisao(nome, um)).toList();
        assertThatThrownBy(() -> montar(new DadosFicha(null, oito)))
                .hasMessage("a ficha pode ter no máximo 7 divisões");
        assertThatThrownBy(() -> montar(new DadosFicha(null, List.of(new DadosFicha.Divisao(" ", um)))))
                .hasMessage("divisão 1: nome é obrigatório");
        assertThatThrownBy(() -> montar(new DadosFicha(null, List.of(new DadosFicha.Divisao("x".repeat(41), um)))))
                .hasMessage("divisão 1: nome pode ter no máximo 40 caracteres");
        assertThatThrownBy(() -> montar(new DadosFicha(null, List.of(new DadosFicha.Divisao("A", List.of())))))
                .hasMessage("divisão 1: precisa de pelo menos um exercício");
        assertThatThrownBy(() -> montar(new DadosFicha(null, List.of(new DadosFicha.Divisao("A", null)))))
                .hasMessage("divisão 1: precisa de pelo menos um exercício");
        assertThatThrownBy(() -> montar(new DadosFicha(null,
                List.of(new DadosFicha.Divisao("A", Collections.nCopies(21, item(SUPINO)))))))
                .hasMessage("divisão 1: pode ter no máximo 20 exercícios");
        assertThatThrownBy(() -> montar(new DadosFicha(null, Collections.singletonList(null))))
                .hasMessage("divisão 1: dados obrigatórios");
    }

    @Test
    void duasDivisoesComOMesmoNomeSaoRecusadas() {
        List<DadosFicha.Item> um = List.of(item(SUPINO));

        assertThatThrownBy(() -> montar(new DadosFicha(null,
                List.of(new DadosFicha.Divisao("Treino A", um), new DadosFicha.Divisao("treino  a", um)))))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessage("duas divisões com o nome \"treino a\"");
    }

    @Test
    void revisaoTemQueSerDepoisDoDiaDaMontagemNaAcademia() {
        // Montada no dia 20 (horário da academia): o dia 21 já serve; o 20, não.
        DadosFicha no21 = new DadosFicha(LocalDate.of(2026, 10, 21), List.of(new DadosFicha.Divisao("A",
                List.of(item(SUPINO)))));
        DadosFicha no20 = new DadosFicha(LocalDate.of(2026, 10, 20), no21.divisoes());

        assertThat(montar(no21).getRevisaoEm()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThatThrownBy(() -> montar(no20))
                .isInstanceOf(DadosInvalidosException.class)
                .hasMessage("revisão deve ser depois do dia em que a ficha foi montada (2026-10-20)");
    }

    @Test
    void revisaoVencidaQuandoADataChega() {
        Ficha ficha = montar(new DadosFicha(LocalDate.of(2026, 11, 20), umaDivisao(item(SUPINO)).divisoes()));

        assertThat(ficha.revisaoVencida(LocalDate.of(2026, 11, 19))).isFalse();
        assertThat(ficha.revisaoVencida(LocalDate.of(2026, 11, 20))).isTrue();
        assertThat(ficha.revisaoVencida(LocalDate.of(2026, 12, 1))).isTrue();
        assertThat(montar(umaDivisao(item(SUPINO))).revisaoVencida(LocalDate.of(2030, 1, 1)))
                .as("sem data de revisão, nunca vence").isFalse();

        ficha.encerrar(MONTAGEM);
        assertThat(ficha.revisaoVencida(LocalDate.of(2026, 12, 1))).as("encerrada não vence").isFalse();
    }

    @Test
    void corrigirTrocaAsDivisoesInteiras() {
        Ficha ficha = montar(umaDivisao(item(SUPINO), item(REMADA)));
        assertThat(ficha.getCorrigidaEm()).isNull();
        Instant depois = MONTAGEM.plusSeconds(86_400);

        ficha.corrigir(new DadosFicha(LocalDate.of(2026, 12, 1),
                List.of(new DadosFicha.Divisao("Único", List.of(new DadosFicha.Item(REMADA, 5, "5", "40 kg", 120, null))))),
                NOMES, depois);

        assertThat(ficha.getCorrigidaEm()).isEqualTo(depois);
        assertThat(ficha.getRevisaoEm()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(ficha.getDivisoes()).extracting(DivisaoDaFicha::getNome).containsExactly("Único");
        assertThat(ficha.getDivisoes().getFirst().getExercicios()).singleElement()
                .satisfies(e -> {
                    assertThat(e.getNome()).isEqualTo("Remada curvada");
                    assertThat(e.getCarga()).isEqualTo("40 kg");
                });
    }

    @Test
    void correcaoInvalidaNaoMudaNada() {
        Ficha ficha = montar(new DadosFicha(LocalDate.of(2026, 11, 1), umaDivisao(item(SUPINO)).divisoes()));
        Map<String, Object> antes = ficha.retrato();

        // A primeira divisão é válida; a segunda não: nada pode mudar, nem a revisão.
        assertThatThrownBy(() -> ficha.corrigir(new DadosFicha(LocalDate.of(2026, 12, 1), List.of(
                new DadosFicha.Divisao("Nova", List.of(item(REMADA))),
                new DadosFicha.Divisao("Ruim", List.of(new DadosFicha.Item(REMADA, 0, "10", null, null, null))))),
                NOMES, MONTAGEM)).isInstanceOf(DadosInvalidosException.class);

        assertThat(ficha.retrato()).isEqualTo(antes);
        assertThat(ficha.getCorrigidaEm()).isNull();
    }

    @Test
    void encerradaNaoMuda() {
        Ficha ficha = montar(umaDivisao(item(SUPINO)));
        Instant fim = MONTAGEM.plusSeconds(3600);

        ficha.encerrar(fim);

        assertThat(ficha.isAtual()).isFalse();
        assertThat(ficha.getEncerradaEm()).isEqualTo(fim);
        assertThatThrownBy(() -> ficha.corrigir(umaDivisao(item(REMADA)), NOMES, fim))
                .isInstanceOf(FichaRecusadaException.class)
                .hasMessageStartingWith("A ficha encerrada fica no histórico e não muda");
        assertThatThrownBy(() -> ficha.encerrar(fim.plusSeconds(1))).isInstanceOf(FichaRecusadaException.class);
        assertThat(ficha.getEncerradaEm()).isEqualTo(fim);
        assertThat(ficha.getDivisoes().getFirst().getExercicios().getFirst().getNome()).isEqualTo("Supino reto");
    }

    @Test
    void retratoTrazAsDivisoesParaATrilha() {
        Ficha ficha = montar(umaDivisao(new DadosFicha.Item(SUPINO, 3, "10", "20 kg", 60, null)));

        Map<String, Object> retrato = ficha.retrato();
        assertThat(retrato).containsEntry("revisaoEm", null).containsEntry("encerradaEm", null);
        List<?> divisoes = (List<?>) retrato.get("divisoes");
        Map<?, ?> divisao = (Map<?, ?>) divisoes.getFirst();
        assertThat(divisoes).hasSize(1);
        assertThat(divisao.get("nome")).isEqualTo("A");
        @SuppressWarnings("unchecked")
        Map<String, Object> exercicio = (Map<String, Object>) ((List<?>) divisao.get("exercicios")).getFirst();
        assertThat(exercicio).containsEntry("exercicioId", SUPINO).containsEntry("nome", "Supino reto")
                .containsEntry("series", 3).containsEntry("repeticoes", "10").containsEntry("carga", "20 kg")
                .containsEntry("descansoSegundos", 60).containsEntry("observacao", null);
    }
}
