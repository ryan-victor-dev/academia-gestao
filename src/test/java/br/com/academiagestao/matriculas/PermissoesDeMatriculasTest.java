package br.com.academiagestao.matriculas;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.auth.Perfil;
import br.com.academiagestao.auth.Usuario;
import br.com.academiagestao.auth.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem pode fazer o quê com as matrículas, com tokens de verdade. A tabela abaixo é a
 * regra de negócio (ADR 0016). As visões do instrutor e do aluno vêm em rotas próprias.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissoesDeMatriculasTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private final Map<Perfil, String> tokens = new EnumMap<>(Perfil.class);
    private final List<UUID> usuarios = new ArrayList<>();
    private final List<UUID> alunos = new ArrayList<>();
    private UUID alunoDoLogin;
    private UUID plano;

    @BeforeAll
    void prepararUmUsuarioDeCadaPerfil() throws Exception {
        plano = UUID.randomUUID();
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 1)",
                plano, "Plano " + plano);
        alunoDoLogin = criarAluno();
        String hash = passwordEncoder.encode(SENHA);
        String sufixo = UUID.randomUUID().toString();
        for (Perfil perfil : Perfil.values()) {
            String email = perfil.name().toLowerCase() + "-" + sufixo + "@teste.local";
            Usuario usuario = usuarioRepository.save(perfil == Perfil.ALUNO
                    ? Usuario.deAluno("Aluno", email, hash, alunoDoLogin)
                    : Usuario.daEquipe(perfil.name(), email, hash, perfil));
            usuarios.add(usuario.getId());
            String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            tokens.put(perfil, JsonPath.read(corpo, "$.token"));
        }
    }

    // Cada caso cria o próprio aluno e a própria matrícula, para um não depender do outro.
    @AfterEach
    void limparMatriculas() {
        for (UUID id : alunos) {
            jdbc.update("DELETE FROM matriculas WHERE aluno_id = ?", id);
        }
    }

    @AfterAll
    void limpar() {
        for (UUID id : usuarios) {
            jdbc.update("DELETE FROM usuarios WHERE id = ?", id);
        }
        for (UUID id : alunos) {
            jdbc.update("DELETE FROM alunos WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM planos WHERE id = ?", plano);
    }

    private UUID criarAluno() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", id, CpfsParaTeste.aleatorio());
        alunos.add(id);
        return id;
    }

    private UUID criarMatricula() {
        UUID id = UUID.randomUUID();
        LocalDate hoje = LocalDate.now(Matricula.FUSO);
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 100, 1, ?, ?, now())""",
                id, criarAluno(), plano, Date.valueOf(hoje), Date.valueOf(hoje.plusMonths(1).minusDays(1)));
        return id;
    }

    private MockHttpServletRequestBuilder requisicao(String operacao) {
        return switch (operacao) {
            case "matricular" -> post("/api/matriculas").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"alunoId\": \"%s\", \"planoId\": \"%s\"}".formatted(criarAluno(), plano));
            case "consultar" -> get("/api/matriculas/" + criarMatricula());
            case "listar" -> get("/api/matriculas").param("alunoId", alunoDoLogin.toString());
            case "cancelar" -> post("/api/matriculas/" + criarMatricula() + "/cancelar")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"Teste de permissão\"}");
            default -> throw new IllegalArgumentException(operacao);
        };
    }

    @ParameterizedTest(name = "{1} em {0} -> {2}")
    @CsvSource({
            // operação,  perfil,    status esperado
            "matricular, ADMIN,     201",
            "matricular, RECEPCAO,  201",
            "matricular, INSTRUTOR, 403",
            "matricular, ALUNO,     403",

            "consultar,  ADMIN,     200",
            "consultar,  RECEPCAO,  200",
            "consultar,  INSTRUTOR, 403",
            "consultar,  ALUNO,     403",

            // O aluno não lista nem as próprias por aqui: o id viria da URL (IDOR).
            "listar,     ADMIN,     200",
            "listar,     RECEPCAO,  200",
            "listar,     INSTRUTOR, 403",
            "listar,     ALUNO,     403",

            "cancelar,   ADMIN,     200",
            "cancelar,   RECEPCAO,  200",
            "cancelar,   INSTRUTOR, 403",
            "cancelar,   ALUNO,     403",
    })
    void matrizDePermissoes(String operacao, Perfil perfil, int statusEsperado) throws Exception {
        mvc.perform(requisicao(operacao).header("Authorization", "Bearer " + tokens.get(perfil)))
                .andExpect(status().is(statusEsperado));
    }

    @ParameterizedTest(name = "{0} sem token -> 401")
    @CsvSource({"matricular", "consultar", "listar", "cancelar"})
    void semTokenNadaFunciona(String operacao) throws Exception {
        mvc.perform(requisicao(operacao)).andExpect(status().isUnauthorized());
    }
}
