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
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem pode fazer o quê com o catálogo de exercícios, com tokens de verdade: cada perfil faz
 * login pelo endpoint e usa o token devolvido. A tabela abaixo é a regra de negócio (ADR 0027).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissoesDeExerciciosTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    ExercicioRepository exercicioRepository;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private final Map<Perfil, String> tokens = new EnumMap<>(Perfil.class);
    // Sempre ativo: alvo das consultas e da edição.
    private Exercicio alvo;
    // Alvo de inativar/reativar, para não afetar as consultas.
    private Exercicio alvoDeStatus;

    @BeforeAll
    void prepararUmUsuarioDeCadaPerfil() throws Exception {
        limpeza.apagarTudo();
        Instant agora = Instant.parse("2026-10-21T12:00:00Z");
        alvo = exercicioRepository.save(Exercicio.novo(new DadosExercicio("Supino", GrupoMuscular.PEITO, null), agora));
        alvoDeStatus = exercicioRepository.save(
                Exercicio.novo(new DadosExercicio("Remada", GrupoMuscular.COSTAS, null), agora));

        UUID alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", alunoId, CpfsParaTeste.aleatorio());
        String hash = passwordEncoder.encode(SENHA);
        for (Perfil perfil : Perfil.values()) {
            String email = perfil.name().toLowerCase() + "@academia.com";
            usuarioRepository.save(perfil == Perfil.ALUNO
                    ? Usuario.deAluno("Aluno", email, hash, alunoId)
                    : Usuario.daEquipe(perfil.name(), email, hash, perfil));
            String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            tokens.put(perfil, JsonPath.read(corpo, "$.token"));
        }
    }

    /** Não deixa nada para trás: os outros testes rodam no mesmo banco, em ordem imprevisível. */
    @AfterAll
    void limpar() {
        limpeza.apagarTudo();
    }

    private MockHttpServletRequestBuilder requisicao(String operacao) {
        return switch (operacao) {
            case "listar" -> get("/api/exercicios");
            case "consultar" -> get("/api/exercicios/" + alvo.getId());
            case "cadastrar" -> post("/api/exercicios").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\": \"Exercício %s\", \"grupoMuscular\": \"PEITO\"}".formatted(UUID.randomUUID()));
            // "*" aceita qualquer versão: aqui o assunto é permissão, não edição simultânea.
            case "editar" -> put("/api/exercicios/" + alvo.getId()).header("If-Match", "*")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\": \"Supino\", \"grupoMuscular\": \"PEITO\"}");
            case "inativar" -> post("/api/exercicios/" + alvoDeStatus.getId() + "/inativar");
            case "reativar" -> post("/api/exercicios/" + alvoDeStatus.getId() + "/reativar");
            // Um exercício novo a cada vez: o do ADMIN e o do instrutor saem, e os outros perfis nem chegam a ele.
            case "excluir" -> delete("/api/exercicios/" + exercicioRepository.save(Exercicio.novo(
                    new DadosExercicio("Exercício " + UUID.randomUUID(), GrupoMuscular.PEITO, null), Instant.now())).getId());
            default -> throw new IllegalArgumentException(operacao);
        };
    }

    @ParameterizedTest(name = "{1} em {0} -> {2}")
    @CsvSource({
            // operação, perfil,    status esperado
            "listar,    ADMIN,     200",
            "listar,    INSTRUTOR, 200",
            "listar,    RECEPCAO,  403",
            "listar,    ALUNO,     403",

            "consultar, ADMIN,     200",
            "consultar, INSTRUTOR, 200",
            "consultar, RECEPCAO,  403",
            "consultar, ALUNO,     403",

            "cadastrar, ADMIN,     201",
            "cadastrar, INSTRUTOR, 201",
            "cadastrar, RECEPCAO,  403",
            "cadastrar, ALUNO,     403",

            "editar,    ADMIN,     200",
            "editar,    INSTRUTOR, 200",
            "editar,    RECEPCAO,  403",
            "editar,    ALUNO,     403",

            "inativar,  ADMIN,     200",
            "inativar,  INSTRUTOR, 200",
            "inativar,  RECEPCAO,  403",
            "inativar,  ALUNO,     403",

            "reativar,  ADMIN,     200",
            "reativar,  INSTRUTOR, 200",
            "reativar,  RECEPCAO,  403",
            "reativar,  ALUNO,     403",

            // O instrutor também exclui o exercício nunca usado (decisão do dono, ADR 0047; antes, só o ADMIN, ADR 0043).
            "excluir,   ADMIN,     204",
            "excluir,   INSTRUTOR, 204",
            "excluir,   RECEPCAO,  403",
            "excluir,   ALUNO,     403",
    })
    void matrizDePermissoes(String operacao, Perfil perfil, int statusEsperado) throws Exception {
        mvc.perform(requisicao(operacao).header("Authorization", "Bearer " + tokens.get(perfil)))
                .andExpect(status().is(statusEsperado));
    }

    @ParameterizedTest(name = "{0} sem token -> 401")
    @CsvSource({"listar", "consultar", "cadastrar", "editar", "inativar", "reativar", "excluir"})
    void semTokenNadaFunciona(String operacao) throws Exception {
        mvc.perform(requisicao(operacao)).andExpect(status().isUnauthorized());
    }
}
