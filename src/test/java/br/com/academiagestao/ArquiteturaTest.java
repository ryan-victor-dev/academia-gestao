package br.com.academiagestao;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
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
                    "..auditoria..", "..pagamentos..", "..noticias..", "..avisos..")
            .because("compartilhado é a base comum: se dependesse de um módulo, todos dependeriam dele");

    @ArchTest
    static final ArchRule authNaoDependeDeOutrosModulos = noClasses()
            .that().resideInAPackage("..auth..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..alunos..", "..planos..", "..matriculas..", "..cobranca..", "..treinos..", "..auditoria..",
                    "..pagamentos..", "..noticias..", "..avisos..")
            .because("os outros módulos dependem do auth, nunca o contrário (sem ciclos)");

    // Porta pública de cada módulo: o que outros módulos podem usar. O resto é interno.
    @ArchTest
    static final ArchRule authSoPelaPortaPublica = soPelaPortaPublica("auth",
            Set.of("LoginsDeAlunos", "ClaimsDoToken", "CredenciaisTemporarias", "RecuperacaoDeAdmin", "NomesDaEquipe",
                    "ContatosDaEquipe", "ContatoDaEquipe"));

    @ArchTest
    static final ArchRule alunosSoPelaPortaPublica = soPelaPortaPublica("alunos",
            Set.of("AlunosParaOutrosModulos", "EstadoDoAluno", "ImpedimentoParaInativar",
                    "AoMudarASituacaoDoAluno", "Pagador", "Contato"));

    @ArchTest
    static final ArchRule planosSoPelaPortaPublica = soPelaPortaPublica("planos",
            Set.of("PlanosParaMatricula", "PlanoOfertado", "FormaPagamento"));

    @ArchTest
    static final ArchRule cobrancaSoPelaPortaPublica = soPelaPortaPublica("cobranca",
            Set.of("CobrancasDasMatriculas", "MatriculaParaCobranca", "Pausa", "Encerramento", "Tipo",
                    "Continuacao", "CobrancasParaPagamentoOnline", "CobrancaEmAberto", "ValorCombinado",
                    "FormaOnline", "ResultadoDoRecebimento", "CobrancasParaAvisos", "FaturaEmAberto", "RegrasDoAtraso"));

    // Para outra empresa, uma classe nova que implementa a porta (ADR 0034); o resto é interno.
    @ArchTest
    static final ArchRule pagamentosSoPelaPortaPublica = soPelaPortaPublica("pagamentos",
            Set.of("ProvedorDePagamento", "Forma", "Pagador", "Pedido", "Gerado", "Estado", "Situacao",
                    "ProvedorIndisponivelException", "AvisoRecusadoException"));

    // Notícias e eventos (ADR 0047): ninguém de fora usa por enquanto.
    @ArchTest
    static final ArchRule noticiasSoPelaPortaPublica = soPelaPortaPublica("noticias", Set.of());

    // Avisos de fatura e da academia (ADR 0047): ninguém de fora usa.
    @ArchTest
    static final ArchRule avisosSoPelaPortaPublica = soPelaPortaPublica("avisos", Set.of());

    @ArchTest
    static final ArchRule cobrancaEmJavaPuro = classes()
            .that().resideInAPackage("..cobranca.dominio..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..cobranca.dominio..")
            .because("as regras da cobrança são Java puro, sem framework nem banco (ADRs 0004 e 0024)");

    @ArchTest
    static final ArchRule entidadesNaoDependemDoSpringNemDeOutrasCamadas = noClasses()
            .that().areAnnotatedWith(Entity.class)
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Service")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .because("as regras de negócio ficam na entidade, sem depender de web, transação ou banco");

    /**
     * R2 (revisão de 2026-10-17): a entidade guarda as regras do próprio módulo. O que ela
     * copia de outro (a forma de pagamento do plano, na matrícula) vira um tipo dela, e quem
     * converte é o service, pela porta pública.
     */
    @ArchTest
    static final ArchRule entidadesSoDependemDoProprioModulo = classes()
            .that().areAnnotatedWith(Entity.class)
            .should(new ArchCondition<JavaClass>("depender só do próprio módulo e do compartilhado") {
                @Override
                public void check(JavaClass entidade, ConditionEvents eventos) {
                    String modulo = modulo(entidade);
                    entidade.getDirectDependenciesFromSelf().stream()
                            .filter(d -> d.getTargetClass().getPackageName().startsWith(BASE))
                            .filter(d -> {
                                String outro = modulo(d.getTargetClass());
                                return !outro.equals(modulo) && !outro.equals("compartilhado");
                            })
                            .forEach(d -> eventos.add(SimpleConditionEvent.violated(d, d.getDescription())));
                }
            })
            .because("a entidade guarda as regras do próprio módulo; o que vem de outro é copiado (ADR 0002)");

    /*
     * Controller -> Service -> Repository (CLAUDE.md; pendências baixas, item 24): o controller
     * recebe, confere o formato e responde; o banco e a transação ficam no service. Antes, cinco
     * controllers faziam SQL ou abriam transação, e nada reclamava.
     */
    @ArchTest
    static final ArchRule controllersNaoAcessamOBanco = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat(resideInAPackage("org.springframework.jdbc..")
                    .or(assignableTo(Repository.class))
                    .or(simpleNameEndingWith("Repository"))
                    .or(assignableTo(EntityManager.class)))
            .because("o banco fica no service e no repositório; o controller só chama o service");

    @ArchTest
    static final ArchRule controllersNaoAbremTransacao = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class)
            .because("a transação é do service (é ele que sabe o que precisa ir junto)");

    @ArchTest
    static final ArchRule metodosDeControllerNaoAbremTransacao = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class)
            .because("a transação é do service (é ele que sabe o que precisa ir junto)");

    // Também os records de entrada e saída, que ficam dentro do controller.
    private static final DescribedPredicate<JavaClass> DA_WEB = DescribedPredicate.describe(
            "um controller ou um tipo de dentro dele", classe -> classe.isAnnotatedWith(RestController.class)
                    || classe.getEnclosingClass().map(c -> c.isAnnotatedWith(RestController.class)).orElse(false));

    @ArchTest
    static final ArchRule servicesNaoDependemDosControllers = noClasses()
            .that().haveSimpleNameEndingWith("Service")
            .should().dependOnClassesThat(DA_WEB)
            .because("o service não sabe quem o chama: os tipos dele não são os da API");

    private static String modulo(JavaClass classe) {
        return classe.getPackageName().substring(BASE.length()).split("\\.")[0];
    }

    // Os carimbos de criação de usuário e plano só são exibidos; nenhuma regra depende deles.
    private static final Set<String> CARIMBOS_COM_A_HORA_DO_SISTEMA = Set.of("Usuario", "Plano");
    private static final Set<Class<?>> TIPOS_DE_DATA = Set.of(Instant.class, LocalDate.class, LocalDateTime.class,
            LocalTime.class, OffsetDateTime.class, ZonedDateTime.class, Year.class, YearMonth.class);

    @ArchTest
    static final ArchRule horaSoPeloRelogioDaAplicacao = noClasses()
            .that().doNotHaveSimpleName("RelogioConfig")
            .should().callMethodWhere(DescribedPredicate.describe("que lê a hora do sistema", chamada -> {
                JavaClass dono = chamada.getTargetOwner();
                String metodo = chamada.getName();
                boolean leHora = metodo.equals("now") && TIPOS_DE_DATA.stream().anyMatch(dono::isEquivalentTo)
                        || metodo.startsWith("system") && dono.isEquivalentTo(Clock.class)
                        || dono.isEquivalentTo(System.class)
                        && (metodo.equals("currentTimeMillis") || metodo.equals("nanoTime"));
                boolean carimbo = metodo.equals("now") && dono.isEquivalentTo(Instant.class)
                        && CARIMBOS_COM_A_HORA_DO_SISTEMA.contains(chamada.getOriginOwner().getSimpleName());
                return leHora && !carimbo;
            }))
            .because("todo \"agora\" sai do Clock injetado (ADR 0023): é ele que os testes param e adiantam");

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
