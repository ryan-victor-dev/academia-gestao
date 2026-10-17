package br.com.academiagestao;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;

import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * As fronteiras do monolito modular (ADR 0002) como regras verificadas. Sem isto, elas
 * seriam só convenção: um import errado passaria sem nenhum teste reclamar. Ver ADR 0015.
 */
@AnalyzeClasses(packages = "br.com.academiagestao", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    private static final String BASE = "br.com.academiagestao.";

    @ArchTest
    static final ArchRule modulosSemCiclos = slices()
            .matching("br.com.academiagestao.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule compartilhadoNaoDependeDeModulos = noClasses()
            .that().resideInAPackage("..compartilhado..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..auth..", "..alunos..", "..planos..", "..matriculas..", "..cobranca..", "..treinos..",
                    "..auditoria..")
            .because("compartilhado é a base comum: se dependesse de um módulo, todos dependeriam dele");

    @ArchTest
    static final ArchRule authNaoDependeDeOutrosModulos = noClasses()
            .that().resideInAPackage("..auth..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..alunos..", "..planos..", "..matriculas..", "..cobranca..", "..treinos..", "..auditoria..")
            .because("os outros módulos dependem do auth, nunca o contrário (sem ciclos)");

    // Porta pública de cada módulo: o que outros módulos podem usar. O resto é interno.
    @ArchTest
    static final ArchRule authSoPelaPortaPublica = soPelaPortaPublica("auth",
            Set.of("LoginsDeAlunos", "ClaimsDoToken", "CredenciaisTemporarias", "RecuperacaoDeAdmin"));

    @ArchTest
    static final ArchRule alunosSoPelaPortaPublica = soPelaPortaPublica("alunos",
            Set.of("AlunosParaMatricula", "EstadoDoAluno", "ImpedimentoParaInativar"));

    @ArchTest
    static final ArchRule planosSoPelaPortaPublica = soPelaPortaPublica("planos",
            Set.of("PlanosParaMatricula", "PlanoOfertado", "FormaPagamento"));

    @ArchTest
    static final ArchRule cobrancaSoPelaPortaPublica = soPelaPortaPublica("cobranca", Set.of());

    @ArchTest
    static final ArchRule cobrancaEmJavaPuro = classes()
            .that().resideInAPackage("..cobranca..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..cobranca..")
            .because("a máquina de estados da mensalidade é Java puro, sem framework nem banco (ADR 0004)");

    @ArchTest
    static final ArchRule entidadesNaoDependemDoSpringNemDeOutrasCamadas = noClasses()
            .that().areAnnotatedWith(Entity.class)
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Service")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .because("as regras de negócio ficam na entidade, sem depender de web, transação ou banco");

    private static ArchRule soPelaPortaPublica(String modulo, Set<String> portaPublica) {
        String pacote = BASE + modulo;
        DescribedPredicate<JavaClass> interna = resideInAPackage(pacote + "..")
                .and(DescribedPredicate.describe("fora da porta pública " + portaPublica,
                        classe -> !portaPublica.contains(classe.getSimpleName())));
        return noClasses()
                .that().resideOutsideOfPackage(pacote + "..")
                .should().dependOnClassesThat(interna)
                .because("de fora do módulo " + modulo + ", só se usa a porta pública dele");
    }
}
