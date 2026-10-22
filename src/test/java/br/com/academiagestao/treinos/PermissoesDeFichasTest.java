package br.com.academiagestao.treinos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.auth.Perfil;
import br.com.academiagestao.auth.Usuario;
import br.com.academiagestao.auth.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem pode fazer o quê com as fichas de treino, com tokens de verdade: cada perfil faz
 * login pelo endpoint e usa o token devolvido. A tabela abaixo é a regra de negócio (ADR 0027):
 * o instrutor e o ADMIN montam e corrigem; a recepção só vê; o aluno usa a visão dele (/me).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissoesDeFichasTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    ExercicioRepository exercicioRepository;

    @Autowired
    FichaRepository fichaRepository;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private final Map<Perfil, String> tokens = new EnumMap<>(Perfil.class);
    private Exercicio supino;
    // Ficha sempre atual: alvo das consultas e da correção.
    private Ficha alvo;
    // Aluno das fichas montadas no teste, para não encerrar o alvo.
    private UUID alunoDeMontar;

    @BeforeAll
    void prepararUmUsuarioDeCadaPerfil() throws Exception {
        limpeza.apagarTudo();
        Instant agora = Instant.parse("2026-10-21T12:00:00Z");
        supino = exercicioRepository.save(Exercicio.novo(new DadosExercicio("Supino", GrupoMuscular.PEITO, null), agora));

        UUID alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", alunoId, CpfsParaTeste.aleatorio());
        alunoDeMontar = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Outro', ?)", alunoDeMontar, CpfsParaTeste.aleatorio());
        String hash = passwordEncoder.encode(SENHA);
        UUID instrutor = null;
        for (Perfil perfil : Perfil.values()) {
            String email = perfil.name().toLowerCase() + "@academia.com";
            Usuario usuario = usuarioRepository.save(perfil == Perfil.ALUNO
                    ? Usuario.deAluno("Aluno", email, hash, alunoId)
                    : Usuario.daEquipe(perfil.name(), email, hash, perfil));
            if (perfil == Perfil.INSTRUTOR) {
                instrutor = usuario.getId();
            }
            String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            tokens.put(perfil, JsonPath.read(corpo, "$.token"));
        }
        alvo = fichaRepository.save(Ficha.montar(alunoId, new DadosFicha(null, List.of(new DadosFicha.Divisao("A",
                List.of(new DadosFicha.Item(supino.getId(), 3, "10", null, null, null))))),
                Map.of(supino.getId(), supino.getNome()), instrutor, agora));
    }

    /** Não deixa nada para trás: os outros testes rodam no mesmo banco, em ordem imprevisível. */
    @AfterAll
    void limpar() {
        limpeza.apagarTudo();
    }

    private String divisoes() {
        return "[{\"nome\": \"A\", \"exercicios\": [{\"exercicioId\": \"%s\", \"series\": 3, \"repeticoes\": \"10\"}]}]"
                .formatted(supino.getId());
    }

    private MockHttpServletRequestBuilder requisicao(String operacao) {
        return switch (operacao) {
            case "montar" -> post("/api/fichas").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"alunoId\": \"%s\", \"divisoes\": %s}".formatted(alunoDeMontar, divisoes()));
            // "*" aceita qualquer versão: aqui o assunto é permissão, não edição simultânea.
            case "corrigir" -> put("/api/fichas/" + alvo.getId()).header("If-Match", "*")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"divisoes\": %s}".formatted(divisoes()));
            case "consultar" -> get("/api/fichas/" + alvo.getId());
            case "atual" -> get("/api/fichas/atual?alunoId=" + alvo.getAlunoId());
            case "historico" -> get("/api/fichas?alunoId=" + alvo.getAlunoId());
            case "minhas" -> get("/api/fichas/me");
            case "vencidas" -> get("/api/fichas/revisoes-vencidas");
            default -> throw new IllegalArgumentException(operacao);
        };
    }

    @ParameterizedTest(name = "{1} em {0} -> {2}")
    @CsvSource({
            // operação, perfil,    status esperado
            "montar,    ADMIN,     201",
            "montar,    INSTRUTOR, 201",
            "montar,    RECEPCAO,  403",
            "montar,    ALUNO,     403",

            "corrigir,  ADMIN,     200",
            "corrigir,  INSTRUTOR, 200",
            "corrigir,  RECEPCAO,  403",
            "corrigir,  ALUNO,     403",

            "consultar, ADMIN,     200",
            "consultar, INSTRUTOR, 200",
            "consultar, RECEPCAO,  200",
            "consultar, ALUNO,     403",

            "atual,     ADMIN,     200",
            "atual,     INSTRUTOR, 200",
            "atual,     RECEPCAO,  200",
            "atual,     ALUNO,     403",

            "historico, ADMIN,     200",
            "historico, INSTRUTOR, 200",
            "historico, RECEPCAO,  200",
            "historico, ALUNO,     403",

            "minhas,    ADMIN,     403",
            "minhas,    INSTRUTOR, 403",
            "minhas,    RECEPCAO,  403",
            "minhas,    ALUNO,     200",

            "vencidas,  ADMIN,     200",
            "vencidas,  INSTRUTOR, 200",
            "vencidas,  RECEPCAO,  403",
            "vencidas,  ALUNO,     403",
    })
    void matrizDePermissoes(String operacao, Perfil perfil, int statusEsperado) throws Exception {
        mvc.perform(requisicao(operacao).header("Authorization", "Bearer " + tokens.get(perfil)))
                .andExpect(status().is(statusEsperado));
    }

    @ParameterizedTest(name = "{0} sem token -> 401")
    @CsvSource({"montar", "corrigir", "consultar", "atual", "historico", "minhas", "vencidas"})
    void semTokenNadaFunciona(String operacao) throws Exception {
        mvc.perform(requisicao(operacao)).andExpect(status().isUnauthorized());
    }
}
