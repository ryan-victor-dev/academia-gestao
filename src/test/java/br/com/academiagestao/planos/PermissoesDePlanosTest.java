package br.com.academiagestao.planos;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.auth.Perfil;
import br.com.academiagestao.auth.Usuario;
import br.com.academiagestao.auth.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
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

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem pode fazer o quê com os planos, com tokens de verdade: cada perfil faz login pelo
 * endpoint e usa o token devolvido. A tabela abaixo é a regra de negócio.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissoesDePlanosTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    PlanoRepository planoRepository;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private final Map<Perfil, String> tokens = new EnumMap<>(Perfil.class);
    // Sempre ativo: alvo das consultas e da edição.
    private Plano alvo;
    // Alvo de inativar/reativar, para não afetar as consultas.
    private Plano alvoDeStatus;

    @BeforeAll
    void prepararUmUsuarioDeCadaPerfil() throws Exception {
        jdbc.update("DELETE FROM usuarios");
        jdbc.update("DELETE FROM planos");
        alvo = planoRepository.save(Plano.novo(new DadosPlano("Mensal", new BigDecimal("129.90"), 1)));
        alvoDeStatus = planoRepository.save(Plano.novo(new DadosPlano("Anual", new BigDecimal("99.90"), 12)));

        UUID alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', '52998224725') ON CONFLICT DO NOTHING", alunoId);
        alunoId = jdbc.queryForObject("SELECT id FROM alunos WHERE cpf = '52998224725'", UUID.class);
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

    private MockHttpServletRequestBuilder requisicao(String operacao) {
        return switch (operacao) {
            case "listar" -> get("/api/planos");
            case "consultar" -> get("/api/planos/" + alvo.getId());
            case "cadastrar" -> post("/api/planos").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\": \"Plano %s\", \"valorMensal\": 100, \"duracaoMeses\": 1}"
                            .formatted(UUID.randomUUID()));
            // "*" aceita qualquer versão: aqui o assunto é permissão, não edição simultânea.
            case "editar" -> put("/api/planos/" + alvo.getId()).header("If-Match", "*")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\": \"Mensal\", \"valorMensal\": 129.90, \"duracaoMeses\": 1}");
            case "inativar" -> post("/api/planos/" + alvoDeStatus.getId() + "/inativar");
            case "reativar" -> post("/api/planos/" + alvoDeStatus.getId() + "/reativar");
            default -> throw new IllegalArgumentException(operacao);
        };
    }

    @ParameterizedTest(name = "{1} em {0} -> {2}")
    @CsvSource({
            // operação, perfil,    status esperado
            "listar,    ADMIN,     200",
            "listar,    RECEPCAO,  200",
            "listar,    INSTRUTOR, 200",
            "listar,    ALUNO,     200",

            "consultar, ADMIN,     200",
            "consultar, RECEPCAO,  200",
            "consultar, INSTRUTOR, 200",
            "consultar, ALUNO,     200",

            "cadastrar, ADMIN,     201",
            "cadastrar, RECEPCAO,  403",
            "cadastrar, INSTRUTOR, 403",
            "cadastrar, ALUNO,     403",

            "editar,    ADMIN,     200",
            "editar,    RECEPCAO,  403",
            "editar,    INSTRUTOR, 403",
            "editar,    ALUNO,     403",

            "inativar,  ADMIN,     200",
            "inativar,  RECEPCAO,  403",
            "inativar,  INSTRUTOR, 403",
            "inativar,  ALUNO,     403",

            "reativar,  ADMIN,     200",
            "reativar,  RECEPCAO,  403",
            "reativar,  INSTRUTOR, 403",
            "reativar,  ALUNO,     403",
    })
    void matrizDePermissoes(String operacao, Perfil perfil, int statusEsperado) throws Exception {
        mvc.perform(requisicao(operacao).header("Authorization", "Bearer " + tokens.get(perfil)))
                .andExpect(status().is(statusEsperado));
    }

    @ParameterizedTest(name = "{0} sem token -> 401")
    @CsvSource({"listar", "consultar", "cadastrar", "editar", "inativar", "reativar"})
    void semTokenNadaFunciona(String operacao) throws Exception {
        mvc.perform(requisicao(operacao)).andExpect(status().isUnauthorized());
    }
}
