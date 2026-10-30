package br.com.academiagestao.alunos;

import br.com.academiagestao.CorreioDeTeste;
import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.auth.Perfil;
import br.com.academiagestao.auth.Usuario;
import br.com.academiagestao.auth.UsuarioRepository;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A recepção troca o e-mail no cadastro do aluno e o login acompanha, a confirmar (decisão do dono,
 * ADR 0045). Antes, o login ficava com o e-mail antigo e o aluno não entrava com o novo. Com login de
 * verdade (o token simulado pularia as regras) e o e-mail ligado por um correio de teste.
 */
@Import({TestcontainersConfiguration.class, CorreioDeTeste.Config.class})
@SpringBootTest
@AutoConfigureMockMvc
class EmailDoCadastroNoLoginTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    AlunoRepository alunos;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    CorreioDeTeste correio;

    private final List<UUID> criadosUsuarios = new ArrayList<>();
    private final List<UUID> criadosAlunos = new ArrayList<>();

    @AfterEach
    void limpar() {
        for (UUID id : criadosUsuarios) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", id);
            limpeza.apagar("usuarios", id);
        }
        for (UUID id : criadosAlunos) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", id);
            limpeza.apagar("alunos", id);
        }
    }

    private static String email() {
        return "cad-" + UUID.randomUUID() + "@teste.local";
    }

    private record AlunoComLogin(UUID alunoId, String cpf, Usuario login) {
    }

    private UUID aluno(String cpf, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email) VALUES (?, 'Aluna', ?, ?)", id, cpf, email);
        criadosAlunos.add(id);
        return id;
    }

    /** Aluno com login de e-mail já confirmado (ele já entrou e confirmou). */
    private AlunoComLogin alunoComLogin(String emailDoCadastro, String emailDoLogin) {
        String cpf = CpfsParaTeste.aleatorio();
        UUID alunoId = aluno(cpf, emailDoCadastro);
        Usuario login = Usuario.deAluno("Aluna", emailDoLogin, passwordEncoder.encode(SENHA), alunoId);
        login.confirmarEmail(emailDoLogin, Instant.now());
        login = usuarios.save(login);
        criadosUsuarios.add(login.getId());
        return new AlunoComLogin(alunoId, cpf, login);
    }

    private ResultActions editar(UUID alunoId, String cpf, String email) throws Exception {
        long versao = alunos.findById(alunoId).orElseThrow().getVersao();
        String campoEmail = email == null ? "" : ", \"email\": \"" + email + "\"";
        return mvc.perform(put("/api/alunos/" + alunoId)
                .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO")))
                .header("If-Match", VersaoEsperada.etag(versao))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"Aluna\", \"cpf\": \"" + cpf + "\"" + campoEmail + "}"));
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"senha\": \"" + SENHA + "\"}"));
    }

    private Usuario loginDe(AlunoComLogin a) {
        return usuarios.findById(a.login().getId()).orElseThrow();
    }

    @Test
    void trocaDoEmailPelaRecepcaoLevaOLoginQueConfirmaONovo() throws Exception {
        String antigo = email();
        String novo = email();
        AlunoComLogin a = alunoComLogin(antigo, antigo);

        editar(a.alunoId(), a.cpf(), novo).andExpect(status().isOk());

        assertThat(loginDe(a).getEmail()).isEqualTo(novo);
        assertThat(loginDe(a).getEmailConfirmadoEm()).isNull();
        login(antigo).andExpect(status().isUnauthorized());
        String token = JsonPath.read(login(novo).andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaConfirmarEmail").value(true))
                .andReturn().getResponse().getContentAsString(), "$.token");
        // O código da confirmação vai para o e-mail novo.
        mvc.perform(post("/api/auth/email/codigo").header("Authorization", "Bearer " + token))
                .andExpect(status().is2xxSuccessful());
        assertThat(correio.ultimoCodigo(novo)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auditoria WHERE entidade_id = ? AND acao = 'EMAIL_DO_CADASTRO'",
                Integer.class, a.login().getId())).isEqualTo(1);
    }

    @Test
    void editarOutroCampoNaoTiraAConfirmacao() throws Exception {
        String atual = email();
        AlunoComLogin a = alunoComLogin(atual, atual);
        Instant confirmadoEm = loginDe(a).getEmailConfirmadoEm();

        // O mesmo e-mail, em maiúsculas: continua o mesmo.
        editar(a.alunoId(), a.cpf(), atual.toUpperCase()).andExpect(status().isOk());

        assertThat(loginDe(a).getEmail()).isEqualTo(atual);
        assertThat(loginDe(a).getEmailConfirmadoEm()).isEqualTo(confirmadoEm);
    }

    @Test
    void loginQueFicouParaTrasAcompanhaNaProximaEdicao() throws Exception {
        // Antes desta regra: a recepção trocou o e-mail do cadastro, e o login ficou com o antigo.
        String novo = email();
        AlunoComLogin a = alunoComLogin(novo, email());

        editar(a.alunoId(), a.cpf(), novo).andExpect(status().isOk());

        assertThat(loginDe(a).getEmail()).isEqualTo(novo);
        login(novo).andExpect(status().isOk());
    }

    @Test
    void emailDeOutroLoginDevolve409ESemMudarNada() throws Exception {
        String antigo = email();
        AlunoComLogin a = alunoComLogin(antigo, antigo);
        String doOutro = email();
        Usuario outro = usuarios.save(Usuario.daEquipe("Outro", doOutro, "hash", Perfil.RECEPCAO));
        criadosUsuarios.add(outro.getId());

        editar(a.alunoId(), a.cpf(), doOutro)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Este e-mail já é usado por outro login"));

        // Cadastro e login na mesma transação: os dois ficam como estavam.
        assertThat(alunos.findById(a.alunoId()).orElseThrow().getEmail()).isEqualTo(antigo);
        assertThat(loginDe(a).getEmail()).isEqualTo(antigo);
        assertThat(loginDe(a).getEmailConfirmadoEm()).isNotNull();
    }

    @Test
    void tirarOEmailDeQuemTemLoginDevolve400() throws Exception {
        String atual = email();
        AlunoComLogin a = alunoComLogin(atual, atual);

        editar(a.alunoId(), a.cpf(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("o aluno tem login: o e-mail não pode ficar vazio"));
        // Vazio passa pelo @Email do controller e chega à regra (com espaços, o @Email já recusa).
        editar(a.alunoId(), a.cpf(), "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("o aluno tem login: o e-mail não pode ficar vazio"));

        assertThat(alunos.findById(a.alunoId()).orElseThrow().getEmail()).isEqualTo(atual);
    }

    @Test
    void cadastroQueJaEstavaSemEmailContinuaEditavel() throws Exception {
        // Antes desta regra, a recepção podia tirar o e-mail de quem tinha login: o login fica como está.
        String doLogin = email();
        AlunoComLogin a = alunoComLogin(null, doLogin);

        editar(a.alunoId(), a.cpf(), null).andExpect(status().isOk());

        assertThat(loginDe(a).getEmail()).isEqualTo(doLogin);
    }

    @Test
    void semLoginOEmailMudaESaiComoAntes() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        UUID alunoId = aluno(cpf, email());
        String novo = email();

        editar(alunoId, cpf, novo).andExpect(status().isOk());
        assertThat(alunos.findById(alunoId).orElseThrow().getEmail()).isEqualTo(novo);

        editar(alunoId, cpf, null).andExpect(status().isOk());
        assertThat(alunos.findById(alunoId).orElseThrow().getEmail()).isNull();
        assertThat(usuarios.findByAlunoId(alunoId)).isEmpty();
    }
}
