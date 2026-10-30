package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data de nascimento da equipe (ADR 0048): obrigatória em todo login novo, nem no futuro nem mais de 120 anos
 * atrás; quem é de antes fica sem até o ADMIN ou a própria pessoa preencher, e a data se corrige, mas não se apaga.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NascimentoDaEquipeTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String CPF = "51687954801";

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

    @Autowired
    Clock relogio;

    private String tokenDoAdmin;
    private LocalDate hoje;

    @BeforeEach
    void cadastrarAdmin() throws Exception {
        limpeza.apagarTudo();
        repository.save(Usuario.daEquipe("Admin", "admin@academia.com", passwordEncoder.encode(SENHA), Perfil.ADMIN));
        tokenDoAdmin = entrar("admin@academia.com");
        hoje = Calendario.hoje(relogio.instant());
    }

    private String entrar(String email) throws Exception {
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.token");
    }

    private ResultActions criar(String email, Object data) throws Exception {
        return criar(email, CPF, data);
    }

    private ResultActions criar(String email, String cpf, Object data) throws Exception {
        String dataJson = data == null ? "null" : "\"" + data + "\"";
        return mvc.perform(post("/api/usuarios").header("Authorization", "Bearer " + tokenDoAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"Rita\", \"email\": \"%s\", \"cpf\": \"%s\", \"dataNascimento\": %s, \"perfil\": \"RECEPCAO\"}"
                        .formatted(email, cpf, dataJson)));
    }

    private ResultActions editar(UUID id, Object data) throws Exception {
        String dataJson = data == null ? "" : ", \"dataNascimento\": \"" + data + "\"";
        return mvc.perform(put("/api/usuarios/" + id).header("Authorization", "Bearer " + tokenDoAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"Antigo\", \"perfil\": \"RECEPCAO\"%s}".formatted(dataJson)));
    }

    private UUID antigoSemData(String email) {
        return repository.save(Usuario.daEquipe("Antigo", email, passwordEncoder.encode(SENHA), Perfil.RECEPCAO)).getId();
    }

    private LocalDate dataNoBanco(UUID id) {
        return jdbc.queryForObject("SELECT data_nascimento FROM usuarios WHERE id = ?", LocalDate.class, id);
    }

    @Test
    void loginNovoGuardaAData() throws Exception {
        String corpo = criar("rita@academia.com", "1990-05-10").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(corpo, "$.id"));

        assertThat(dataNoBanco(id)).isEqualTo(LocalDate.of(1990, 5, 10));
        mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + tokenDoAdmin))
                .andExpect(jsonPath("$[?(@.email == 'rita@academia.com')].dataNascimento").value("1990-05-10"));
        assertThat(jdbc.queryForObject("SELECT alteracoes -> 'dataNascimento' ->> 'depois' FROM auditoria "
                + "WHERE entidade_id = ? AND acao = 'CRIADO'", String.class, id)).isEqualTo("1990-05-10");
    }

    @Test
    void loginNovoSemDataOuForaDosLimitesEhRecusado() throws Exception {
        criar("rita@academia.com", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("dataNascimento"));
        criar("rita@academia.com", hoje.plusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("data de nascimento não pode estar no futuro"));
        criar("rita@academia.com", hoje.minusYears(120).minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("data de nascimento pode ser de no máximo 120 anos atrás"));
        assertThat(repository.existsByEmail("rita@academia.com")).isFalse();
    }

    @Test
    void osLimitesValem() throws Exception {
        criar("hoje@academia.com", hoje).andExpect(status().isCreated());
        criar("velho@academia.com", CpfsParaTeste.aleatorio(), hoje.minusYears(120)).andExpect(status().isCreated());
    }

    @Test
    void quemEhDeAntesContinuaSemDataEASemDataNoPedidoAAtualFica() throws Exception {
        UUID antigo = antigoSemData("antigo@academia.com");
        editar(antigo, null).andExpect(status().isOk()).andExpect(jsonPath("$.dataNascimento").doesNotExist());
        assertThat(dataNoBanco(antigo)).isNull();

        editar(antigo, "1985-01-31").andExpect(status().isOk()).andExpect(jsonPath("$.dataNascimento").value("1985-01-31"));
        editar(antigo, null).andExpect(status().isOk());
        assertThat(dataNoBanco(antigo)).isEqualTo(LocalDate.of(1985, 1, 31));

        editar(antigo, hoje.plusDays(1)).andExpect(status().isBadRequest());
        assertThat(dataNoBanco(antigo)).isEqualTo(LocalDate.of(1985, 1, 31));
    }

    @Test
    void aPropriaPessoaInformaAPropria() throws Exception {
        UUID antigo = antigoSemData("antigo@academia.com");
        String token = entrar("antigo@academia.com");

        mvc.perform(put("/api/me/nascimento").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataNascimento\": \"1992-07-15\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataNascimento").value("1992-07-15"));
        assertThat(dataNoBanco(antigo)).isEqualTo(LocalDate.of(1992, 7, 15));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.dataNascimento").value("1992-07-15"));
        assertThat(jdbc.queryForObject("SELECT alteracoes -> 'dataNascimento' ->> 'depois' FROM auditoria "
                + "WHERE entidade_id = ? AND acao = 'NASCIMENTO_INFORMADO'", String.class, antigo)).isEqualTo("1992-07-15");

        mvc.perform(put("/api/me/nascimento").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataNascimento\": null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/nascimento").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataNascimento\": \"" + hoje.plusDays(1) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(dataNoBanco(antigo)).isEqualTo(LocalDate.of(1992, 7, 15));
    }

    /** O aluno não tem a data no login: a dele fica no cadastro, e quem corrige é a recepção. */
    @Test
    void oAlunoNaoUsaARotaDaEquipe() throws Exception {
        mvc.perform(put("/api/me/nascimento").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                .authorities(() -> "ROLE_ALUNO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dataNascimento\": \"1992-07-15\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void oBancoNaoAceitaDataEmLoginDeAlunoNemAbsurda() {
        UUID id = antigoSemData("antigo@academia.com");
        assertThat(tentarNoBanco("UPDATE usuarios SET data_nascimento = '1899-12-31' WHERE id = '" + id + "'"))
                .contains("ck_usuarios_nascimento_valido");
        UUID aluno = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Ana', ?)", aluno, CpfsParaTeste.aleatorio());
        assertThat(tentarNoBanco("UPDATE usuarios SET data_nascimento = '1990-01-01', perfil = 'ALUNO', aluno_id = '" + aluno
                + "' WHERE id = '" + id + "'")).contains("ck_usuarios_nascimento_so_da_equipe");
    }

    private String tentarNoBanco(String sql) {
        try {
            jdbc.update(sql);
            return "aceitou";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
