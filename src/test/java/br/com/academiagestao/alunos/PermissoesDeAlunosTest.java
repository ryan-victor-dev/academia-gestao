package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.auth.Perfil;
import br.com.academiagestao.auth.Usuario;
import br.com.academiagestao.auth.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

import java.util.EnumMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem pode fazer o quê com o cadastro de alunos, com tokens de verdade: cada perfil
 * faz login pelo endpoint e usa o token devolvido. A tabela abaixo é a regra de negócio.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissoesDeAlunosTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    AlunoRepository alunoRepository;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private final Map<Perfil, String> tokens = new EnumMap<>(Perfil.class);
    private Aluno alvo;

    @BeforeAll
    void prepararUmUsuarioDeCadaPerfil() throws Exception {
        jdbc.update("DELETE FROM usuarios");
        alunoRepository.deleteAll();

        alvo = alunoRepository.save(Aluno.novo(new DadosAluno("Aluno Alvo", CpfsParaTeste.aleatorio(),
                null, null, null, null)));
        String hash = passwordEncoder.encode(SENHA);
        for (Perfil perfil : Perfil.values()) {
            String email = perfil.name().toLowerCase() + "@academia.com";
            usuarioRepository.save(perfil == Perfil.ALUNO
                    ? Usuario.deAluno("Aluno Alvo", email, hash, alvo.getId())
                    : Usuario.daEquipe(perfil.name(), email, hash, perfil));
            tokens.put(perfil, login(email));
        }
    }

    private String login(String email) throws Exception {
        String corpo = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.token");
    }

    private Aluno alunoComEmail() {
        String cpf = CpfsParaTeste.aleatorio();
        return alunoRepository.save(Aluno.novo(new DadosAluno("Aluno " + cpf, cpf, cpf + "@email.com",
                null, null, null)));
    }

    private MockHttpServletRequestBuilder requisicao(String operacao) {
        String base = "/api/alunos/" + alvo.getId();
        String corpo = "{\"nome\": \"Aluno Alvo\", \"cpf\": \"%s\"}";
        return switch (operacao) {
            case "listar" -> get("/api/alunos");
            case "consultar" -> get(base);
            case "meu-cadastro" -> get("/api/alunos/me");
            case "cadastrar" -> post("/api/alunos").contentType(MediaType.APPLICATION_JSON)
                    .content(corpo.formatted(CpfsParaTeste.aleatorio()));
            // "*" aceita qualquer versão: aqui o assunto é permissão, não edição simultânea.
            case "editar" -> put(base).header("If-Match", "*").contentType(MediaType.APPLICATION_JSON)
                    .content(corpo.formatted(alvo.getCpf()));
            case "inativar" -> post(base + "/inativar");
            case "reativar" -> post(base + "/reativar");
            // Cada tentativa usa um aluno novo, para a segunda não esbarrar no login da primeira.
            case "criar-login" -> post("/api/alunos/" + alunoComEmail().getId() + "/login");
            default -> throw new IllegalArgumentException(operacao);
        };
    }

    @ParameterizedTest(name = "{1} em {0} -> {2}")
    @CsvSource({
            // operação,    perfil,    status esperado
            "listar,        ADMIN,     200",
            "listar,        RECEPCAO,  200",
            "listar,        INSTRUTOR, 200",
            "listar,        ALUNO,     403",

            "consultar,     ADMIN,     200",
            "consultar,     RECEPCAO,  200",
            "consultar,     INSTRUTOR, 200",
            "consultar,     ALUNO,     403",

            "cadastrar,     ADMIN,     201",
            "cadastrar,     RECEPCAO,  201",
            "cadastrar,     INSTRUTOR, 403",
            "cadastrar,     ALUNO,     403",

            "editar,        ADMIN,     200",
            "editar,        RECEPCAO,  200",
            "editar,        INSTRUTOR, 403",
            "editar,        ALUNO,     403",

            "inativar,      ADMIN,     200",
            "inativar,      RECEPCAO,  200",
            "inativar,      INSTRUTOR, 403",
            "inativar,      ALUNO,     403",

            "reativar,      ADMIN,     200",
            "reativar,      RECEPCAO,  200",
            "reativar,      INSTRUTOR, 403",
            "reativar,      ALUNO,     403",

            "criar-login,   ADMIN,     201",
            "criar-login,   RECEPCAO,  201",
            "criar-login,   INSTRUTOR, 403",
            "criar-login,   ALUNO,     403",

            "meu-cadastro,  ADMIN,     403",
            "meu-cadastro,  RECEPCAO,  403",
            "meu-cadastro,  INSTRUTOR, 403",
            "meu-cadastro,  ALUNO,     200",
    })
    void matrizDePermissoes(String operacao, Perfil perfil, int statusEsperado) throws Exception {
        mvc.perform(requisicao(operacao).header("Authorization", "Bearer " + tokens.get(perfil)))
                .andExpect(status().is(statusEsperado));
    }

    @ParameterizedTest(name = "{0} sem token -> 401")
    @CsvSource({"listar", "consultar", "meu-cadastro", "cadastrar", "editar", "inativar", "reativar", "criar-login"})
    void semTokenNadaFunciona(String operacao) throws Exception {
        mvc.perform(requisicao(operacao)).andExpect(status().isUnauthorized());
    }

    @Test
    void alunoLogadoVeOsPropriosDadosPeloTokenDoLogin() throws Exception {
        mvc.perform(get("/api/alunos/me").header("Authorization", "Bearer " + tokens.get(Perfil.ALUNO)))
                .andExpect(jsonPath("$.id").value(alvo.getId().toString()))
                .andExpect(jsonPath("$.nome").value("Aluno Alvo"));
    }
}
