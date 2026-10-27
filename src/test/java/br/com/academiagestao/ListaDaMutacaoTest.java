package br.com.academiagestao;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lista do PIT (perfil {@code mutacao} do pom.xml) é escrita à mão e ficava para trás: os
 * treinos, os limitadores e a auditoria entraram depois e não estavam nela (pendências baixas,
 * item 27). Este teste a confere com o código, sem rodar o PIT:
 * <ul>
 *   <li>todo teste de unidade (sem Spring e sem banco) está nos testes do PIT;</li>
 *   <li>a classe de cada teste de unidade ({@code FooTest} testa {@code Foo}) é mutada, menos os
 *       services, que os testes com banco é que cobrem (sem o banco, sobrariam mutantes falsos);</li>
 *   <li>toda entidade e todo o domínio da cobrança (Java puro, ADR 0024) são mutados;</li>
 *   <li>nenhuma entrada aponta para o que não existe mais.</li>
 * </ul>
 */
class ListaDaMutacaoTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path TESTES = Path.of("src/test/java");
    private static final String BASE = "br.com.academiagestao.";

    // O que faz um teste precisar do Spring ou do banco: o PIT roda cada mutante sem nenhum dos dois.
    private static final List<String> SINAIS_DE_INTEGRACAO = List.of("@SpringBootTest", "@WebMvcTest",
            "TestcontainersConfiguration", "PostgreSQLContainer", "@AnalyzeClasses");

    private static List<String> classesMutadas;
    private static List<String> testesDoPit;
    private static Map<String, String> fontesDosTestes;

    @BeforeAll
    static void lerOPomEOsTestes() throws Exception {
        Element perfil = perfilDaMutacao();
        classesMutadas = parametros(perfil, "targetClasses");
        testesDoPit = parametros(perfil, "targetTests");
        try (Stream<Path> arquivos = Files.walk(TESTES)) {
            fontesDosTestes = arquivos.filter(a -> a.toString().endsWith("Test.java"))
                    .collect(Collectors.toMap(a -> nomeDaClasse(TESTES, a), ListaDaMutacaoTest::ler));
        }
    }

    @Test
    void todoTesteDeUnidadeEstaNoPit() {
        assertThat(testesDeUnidade()).allSatisfy(teste ->
                assertThat(casa(testesDoPit, teste)).as("%s fora dos targetTests do pom.xml", teste).isTrue());
    }

    @Test
    void aClasseDeCadaTesteDeUnidadeEhMutada() {
        List<String> testadas = testesDeUnidade().stream()
                .map(teste -> teste.substring(0, teste.length() - "Test".length()))
                .filter(classe -> Files.exists(fonte(MAIN, classe)))
                .filter(classe -> !classe.endsWith("Service"))
                .toList();
        assertThat(testadas).isNotEmpty().allSatisfy(classe ->
                assertThat(casa(classesMutadas, classe)).as("%s fora dos targetClasses do pom.xml", classe).isTrue());
    }

    @Test
    void entidadesEDominioDaCobrancaSaoMutados() throws IOException {
        List<String> obrigatorias = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(MAIN)) {
            arquivos.filter(a -> a.toString().endsWith(".java")).forEach(a -> {
                String classe = nomeDaClasse(MAIN, a);
                if (ler(a).contains("\n@Entity") || classe.startsWith(BASE + "cobranca.dominio.")) {
                    obrigatorias.add(classe);
                }
            });
        }
        assertThat(obrigatorias).isNotEmpty().allSatisfy(classe ->
                assertThat(casa(classesMutadas, classe)).as("%s fora dos targetClasses do pom.xml", classe).isTrue());
    }

    @Test
    void nenhumaEntradaApontaParaOQueNaoExiste() {
        assertThat(classesMutadas).allSatisfy(entrada -> assertThat(entrada.contains("*")
                ? existeAlgumaQueCasa(MAIN, entrada) : Files.exists(fonte(MAIN, entrada)))
                .as("targetClasses %s não existe", entrada).isTrue());
        assertThat(testesDoPit).allSatisfy(entrada -> assertThat(testesDeUnidade().stream()
                .anyMatch(teste -> casa(List.of(entrada), teste)))
                .as("targetTests %s não é um teste de unidade que exista", entrada).isTrue());
    }

    private static List<String> testesDeUnidade() {
        return fontesDosTestes.entrySet().stream()
                .filter(e -> SINAIS_DE_INTEGRACAO.stream().noneMatch(e.getValue()::contains))
                .map(Map.Entry::getKey)
                // Este lê arquivos do projeto: não testa regra nenhuma que o PIT mute.
                .filter(teste -> !teste.equals(ListaDaMutacaoTest.class.getName()))
                .sorted()
                .toList();
    }

    /** Os padrões do PIT: {@code *} é qualquer trecho do nome. */
    private static boolean casa(List<String> padroes, String classe) {
        return padroes.stream().anyMatch(p -> Pattern.matches(
                Stream.of(p.split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*")), classe));
    }

    private static boolean existeAlgumaQueCasa(Path raiz, String padrao) {
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            return arquivos.filter(a -> a.toString().endsWith(".java"))
                    .anyMatch(a -> casa(List.of(padrao), nomeDaClasse(raiz, a)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Element perfilDaMutacao() throws Exception {
        NodeList perfis = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile())
                .getElementsByTagName("profile");
        for (int i = 0; i < perfis.getLength(); i++) {
            Element perfil = (Element) perfis.item(i);
            if ("mutacao".equals(perfil.getElementsByTagName("id").item(0).getTextContent())) {
                return perfil;
            }
        }
        throw new IllegalStateException("o pom.xml não tem o perfil mutacao");
    }

    private static List<String> parametros(Element perfil, String lista) {
        NodeList params = ((Element) perfil.getElementsByTagName(lista).item(0)).getElementsByTagName("param");
        List<String> valores = new ArrayList<>();
        for (int i = 0; i < params.getLength(); i++) {
            valores.add(params.item(i).getTextContent().strip());
        }
        assertThat(valores).as(lista).isNotEmpty();
        return valores;
    }

    private static String nomeDaClasse(Path raiz, Path arquivo) {
        String relativo = raiz.relativize(arquivo).toString();
        return relativo.substring(0, relativo.length() - ".java".length()).replace('/', '.').replace('\\', '.');
    }

    private static Path fonte(Path raiz, String classe) {
        return raiz.resolve(classe.replace('.', '/') + ".java");
    }

    private static String ler(Path arquivo) {
        try {
            return Files.readString(arquivo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
