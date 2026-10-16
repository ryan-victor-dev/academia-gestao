package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADMIN que esqueceu a senha recupera o acesso por um comando rodado no servidor. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RecuperacaoDeAdminTest {

    private static final String SENHA = "senha-esquecida-123";
    private static final Pattern SENHA_IMPRESSA = Pattern.compile("Senha temporária do ADMIN \\S+: (\\S+)");

    @Autowired
    RecuperacaoDeAdmin recuperacao;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final ByteArrayOutputStream terminal = new ByteArrayOutputStream();
    private final ByteArrayOutputStream terminalDeErro = new ByteArrayOutputStream();
    private Usuario admin;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM usuarios");
        admin = repository.save(Usuario.daEquipe("Dona", "dona@academia.com", passwordEncoder.encode(SENHA), Perfil.ADMIN));
        recuperacao.redirecionarSaida(new PrintStream(terminal, true, StandardCharsets.UTF_8),
                new PrintStream(terminalDeErro, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restaurar() {
        recuperacao.redirecionarSaida(System.out, System.err);
    }

    private void rodar(String... argumentos) {
        recuperacao.run(new DefaultApplicationArguments(argumentos));
    }

    private String senhaImpressa() {
        Matcher m = SENHA_IMPRESSA.matcher(terminal.toString(StandardCharsets.UTF_8));
        assertThat(m.find()).as("o terminal deveria mostrar a senha temporária").isTrue();
        return m.group(1);
    }

    private ResultActions login(String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"dona@academia.com\", \"senha\": \"%s\"}".formatted(senha)));
    }

    @Test
    void geraSenhaTemporariaQueFuncionaEExigeTroca(CapturedOutput log) throws Exception {
        String sessaoAntiga = JsonPath.read(login(SENHA).andReturn().getResponse().getContentAsString(), "$.token");

        rodar("--recuperar-admin=Dona@Academia.com");

        String temporaria = senhaImpressa();
        assertThat(recuperacao.getExitCode()).isZero();
        login(temporaria).andExpect(status().isOk()).andExpect(jsonPath("$.precisaTrocarSenha").value(true));
        login(SENHA).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + sessaoAntiga))
                .andExpect(status().isUnauthorized());
        // Auditoria no log, mas sem a senha.
        assertThat(log.getAll()).contains("Acesso do ADMIN dona@academia.com recuperado").doesNotContain(temporaria);
    }

    @Test
    void adminInativoEhReativado() throws Exception {
        admin.desativar();
        repository.save(admin);

        rodar("--recuperar-admin=dona@academia.com");

        assertThat(terminal.toString(StandardCharsets.UTF_8)).contains("foi reativado");
        login(senhaImpressa()).andExpect(status().isOk());
    }

    @Test
    void naoMexeEmQuemNaoEhAdmin() throws Exception {
        repository.save(Usuario.daEquipe("Rita", "rita@academia.com", passwordEncoder.encode(SENHA), Perfil.RECEPCAO));

        rodar("--recuperar-admin=rita@academia.com");

        assertThat(recuperacao.getExitCode()).isEqualTo(1);
        assertThat(terminalDeErro.toString(StandardCharsets.UTF_8)).contains("não é ADMIN");
        assertThat(terminal.toString(StandardCharsets.UTF_8)).doesNotContain("Senha temporária");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"rita@academia.com\", \"senha\": \"%s\"}".formatted(SENHA)))
                .andExpect(status().isOk());
    }

    @Test
    void emailInexistenteOuVazioFalhaSemMexerEmNada() throws Exception {
        rodar("--recuperar-admin=ninguem@academia.com");
        assertThat(recuperacao.getExitCode()).isEqualTo(1);
        assertThat(terminalDeErro.toString(StandardCharsets.UTF_8)).contains("Nenhum login com o e-mail");

        rodar("--recuperar-admin=");
        assertThat(terminalDeErro.toString(StandardCharsets.UTF_8)).contains("Informe o e-mail");

        login(SENHA).andExpect(status().isOk());
    }

    @Test
    void semAOpcaoNaoFazNada() throws Exception {
        rodar("--outra-coisa=1");

        assertThat(terminal.toString(StandardCharsets.UTF_8)).isEmpty();
        login(SENHA).andExpect(status().isOk());
    }

    @Test
    void reconheceAOpcaoNosArgumentosDoMain() {
        assertThat(RecuperacaoDeAdmin.foiPedida(new String[]{"--recuperar-admin=a@b.com"})).isTrue();
        assertThat(RecuperacaoDeAdmin.foiPedida(new String[]{"--recuperar-admin"})).isTrue();
        assertThat(RecuperacaoDeAdmin.foiPedida(new String[]{"--recuperar-administrador=x"})).isFalse();
        assertThat(RecuperacaoDeAdmin.foiPedida(new String[]{})).isFalse();
    }
}
