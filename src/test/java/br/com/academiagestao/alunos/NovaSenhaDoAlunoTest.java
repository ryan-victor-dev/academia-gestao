package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.auth.Usuario;
import br.com.academiagestao.auth.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O aluno esqueceu a senha: a recepção gera uma nova senha temporária. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NovaSenhaDoAlunoTest {

    private static final String SENHA = "senha-da-ana-123";
    private static final RequestPostProcessor RECEPCAO =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));

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

    private Aluno ana;

    @BeforeEach
    void cadastrarAlunaComLogin() {
        jdbc.update("DELETE FROM usuarios");
        alunoRepository.deleteAll();
        ana = alunoRepository.save(Aluno.novo(new DadosAluno("Ana", "52998224725", "ana@email.com", null, null, null)));
        usuarioRepository.save(Usuario.deAluno("Ana", "ana@email.com", passwordEncoder.encode(SENHA), ana.getId()));
    }

    private ResultActions login(String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"ana@email.com\", \"senha\": \"%s\"}".formatted(senha)));
    }

    private ResultActions novaSenha(UUID alunoId) throws Exception {
        return mvc.perform(post("/api/alunos/" + alunoId + "/login/nova-senha").with(RECEPCAO));
    }

    @Test
    void recepcaoGeraNovaSenhaEASessaoAntigaCai() throws Exception {
        String sessaoAntiga = JsonPath.read(login(SENHA).andReturn().getResponse().getContentAsString(), "$.token");

        String corpo = novaSenha(ana.getId())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("ana@email.com"))
                .andReturn().getResponse().getContentAsString();
        String temporaria = JsonPath.read(corpo, "$.senhaTemporaria");

        mvc.perform(get("/api/alunos/me").header("Authorization", "Bearer " + sessaoAntiga))
                .andExpect(status().isUnauthorized());
        login(SENHA).andExpect(status().isUnauthorized());
        login(temporaria).andExpect(status().isOk()).andExpect(jsonPath("$.precisaTrocarSenha").value(true));
    }

    @Test
    void alunoSemLoginDevolve404() throws Exception {
        Aluno semLogin = alunoRepository.save(Aluno.novo(new DadosAluno("Bia", "11144477735", null, null, null, null)));

        novaSenha(semLogin.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Este aluno ainda não tem login"));
    }

    @Test
    void alunoInativoDevolve409() throws Exception {
        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO));

        novaSenha(ana.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Aluno inativo"));
    }

    @Test
    void alunoInexistenteDevolve404() throws Exception {
        novaSenha(UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
