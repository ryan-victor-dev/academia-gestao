package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login com senha temporária: só dá para trocar a senha; depois disso, uso normal. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TrocaDeSenhaTest {

    private static final String TEMPORARIA = "temporaria-123456";
    private static final String NOVA = "minha-senha-nova-123";

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cadastrarRecepcaoComSenhaTemporaria() {
        limpeza.apagarTudo();
        Usuario recepcao = Usuario.daEquipe("Ana", "ana@academia.com", passwordEncoder.encode(TEMPORARIA), Perfil.RECEPCAO);
        recepcao.exigirTrocaDeSenha();
        repository.save(recepcao);
    }

    private ResultActions login(String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"ana@academia.com\", \"senha\": \"%s\"}".formatted(senha)));
    }

    private String token(ResultActions resposta) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions trocar(String token, String atual, String nova) throws Exception {
        return mvc.perform(post("/api/auth/trocar-senha").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"senhaAtual\": \"%s\", \"novaSenha\": \"%s\"}".formatted(atual, nova)));
    }

    @Test
    void loginComSenhaTemporariaAvisaQuePrecisaTrocar() throws Exception {
        login(TEMPORARIA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaTrocarSenha").value(true));
    }

    @Test
    void comSenhaTemporariaNenhumaRotaDoPerfilFunciona() throws Exception {
        String token = token(login(TEMPORARIA));

        // A recepção normalmente lista alunos; com senha temporária, não.
        mvc.perform(get("/api/alunos").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Troca de senha obrigatória"));
    }

    @Test
    void comSenhaTemporariaAteRotaSemRegraDePerfilEhBloqueada() throws Exception {
        // Segunda camada: o filtro barra antes de qualquer controller. Uma rota inexistente
        // daria 404 para um usuário normal; com senha temporária, nem chega a procurar.
        String token = token(login(TEMPORARIA));

        mvc.perform(get("/api/qualquer-rota").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Troca de senha obrigatória"));
    }

    @Test
    void comSenhaTemporariaAindaVeQuemE() throws Exception {
        String token = token(login(TEMPORARIA));

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaTrocarSenha").value(true));
    }

    @Test
    void trocarSenhaLiberaOUsoComONovoToken() throws Exception {
        String temporario = token(login(TEMPORARIA));

        String novoToken = token(trocar(temporario, TEMPORARIA, NOVA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaTrocarSenha").value(false)));

        mvc.perform(get("/api/alunos").header("Authorization", "Bearer " + novoToken))
                .andExpect(status().isOk());
        assertThat(repository.findByEmail("ana@academia.com").orElseThrow().isPrecisaTrocarSenha()).isFalse();
    }

    @Test
    void depoisDaTrocaASenhaAntigaNaoEntraMaisEANovaSim() throws Exception {
        trocar(token(login(TEMPORARIA)), TEMPORARIA, NOVA).andExpect(status().isOk());

        login(TEMPORARIA).andExpect(status().isUnauthorized());
        login(NOVA).andExpect(status().isOk()).andExpect(jsonPath("$.precisaTrocarSenha").value(false));
    }

    @Test
    void senhaAtualErradaNaoTroca() throws Exception {
        trocar(token(login(TEMPORARIA)), "chute-errado-123", NOVA)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("senha atual incorreta"));
        login(TEMPORARIA).andExpect(status().isOk());
    }

    @Test
    void novaSenhaCurtaNaoTroca() throws Exception {
        trocar(token(login(TEMPORARIA)), TEMPORARIA, "curta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("a senha precisa ter pelo menos 12 caracteres"));
    }

    @Test
    void novaSenhaIgualAAtualNaoTroca() throws Exception {
        trocar(token(login(TEMPORARIA)), TEMPORARIA, TEMPORARIA)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("a nova senha precisa ser diferente da atual"));
    }

    @Test
    void semTokenNaoTroca() throws Exception {
        mvc.perform(post("/api/auth/trocar-senha").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\": \"x\", \"novaSenha\": \"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioComSenhaNormalTambemPodeTrocar() throws Exception {
        String token = token(login(TEMPORARIA));
        String normal = token(trocar(token, TEMPORARIA, NOVA));

        trocar(normal, NOVA, "outra-senha-forte-456").andExpect(status().isOk());
        login("outra-senha-forte-456").andExpect(status().isOk());
    }
}
