package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Com um token roubado, alguém tentaria descobrir a senha atual pela troca de senha.
 * Ela passa pelo mesmo limite de tentativas do login, na mesma conta.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "academia.login.falhas-por-conta=3")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LimiteDaTrocaDeSenhaTest {

    private static final String SENHA = "senha-da-ana-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void cadastrarELogar() throws Exception {
        jdbc.update("DELETE FROM tentativas_login");
        jdbc.update("DELETE FROM usuarios");
        repository.save(Usuario.daEquipe("Ana", "ana@academia.com", passwordEncoder.encode(SENHA), Perfil.RECEPCAO));
        token = JsonPath.read(login().andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"ana@academia.com\", \"senha\": \"%s\"}".formatted(SENHA)));
    }

    private ResultActions trocar(String senhaAtual) throws Exception {
        return mvc.perform(post("/api/auth/trocar-senha").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"senhaAtual\": \"%s\", \"novaSenha\": \"senha-nova-da-ana-123\"}".formatted(senhaAtual)));
    }

    @Test
    void chutesDaSenhaAtualSaoLimitadosAteParaASenhaCerta() throws Exception {
        for (int i = 0; i < 3; i++) {
            trocar("chute-" + i + "-xxxxxx").andExpect(status().isBadRequest());
        }

        trocar(SENHA)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void contaSobAtaquePelaTrocaDeSenhaTambemTemOLoginBloqueado() throws Exception {
        for (int i = 0; i < 3; i++) {
            trocar("chute-" + i + "-xxxxxx");
        }

        login().andExpect(status().isTooManyRequests());
    }

    @Test
    void trocaCertaZeraAsFalhas() throws Exception {
        trocar("chute-1-xxxxxx");
        trocar("chute-2-xxxxxx");
        trocar(SENHA).andExpect(status().isOk());

        // Zeradas, as 2 falhas de antes não contam: mais 2 erros (limite 3) ainda não bloqueiam.
        loginCom("errada-1-xxxxxx").andExpect(status().isUnauthorized());
        loginCom("errada-2-xxxxxx").andExpect(status().isUnauthorized());
        loginCom("senha-nova-da-ana-123").andExpect(status().isOk());
    }

    private ResultActions loginCom(String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"ana@academia.com\", \"senha\": \"%s\"}".formatted(senha)));
    }

    @Test
    void chutesVaoParaOLogSemASenha(CapturedOutput log) throws Exception {
        trocar("chute-secreto-xxxxxx");

        assertThat(log.getAll())
                .contains("Troca de senha recusada (senha atual errada): conta ana@academia.com")
                .doesNotContain("chute-secreto");
    }
}
