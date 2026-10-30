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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CPF da equipe (ADR 0047, decisão 1): obrigatório em todo login novo, sem repetir; quem é de antes
 * fica sem até o ADMIN preencher, e o CPF se corrige, mas não se apaga.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CpfDaEquipeTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String CPF = "51687954801";
    private static final String OUTRO_CPF = "66906010484";

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
    DataSource dataSource;

    private String tokenDoAdmin;

    @BeforeEach
    void cadastrarAdmin() throws Exception {
        limpeza.apagarTudo();
        repository.save(Usuario.daEquipe("Admin", "admin@academia.com", passwordEncoder.encode(SENHA), Perfil.ADMIN));
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"admin@academia.com\", \"senha\": \"%s\"}".formatted(SENHA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        tokenDoAdmin = JsonPath.read(corpo, "$.token");
    }

    private ResultActions criar(String email, String cpf) throws Exception {
        String cpfJson = cpf == null ? "null" : "\"" + cpf + "\"";
        return mvc.perform(post("/api/usuarios").header("Authorization", "Bearer " + tokenDoAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"Rita\", \"email\": \"%s\", \"cpf\": %s, \"dataNascimento\": \"1990-05-10\", \"perfil\": \"RECEPCAO\"}".formatted(email, cpfJson)));
    }

    private ResultActions editar(UUID id, String nome, String cpf) throws Exception {
        String cpfJson = cpf == null ? "" : ", \"cpf\": \"" + cpf + "\"";
        return mvc.perform(put("/api/usuarios/" + id).header("Authorization", "Bearer " + tokenDoAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"%s\", \"perfil\": \"RECEPCAO\"%s}".formatted(nome, cpfJson)));
    }

    private UUID antigoSemCpf(String email) {
        return repository.save(Usuario.daEquipe("Antigo", email, passwordEncoder.encode(SENHA), Perfil.RECEPCAO)).getId();
    }

    private String cpfNoBanco(UUID id) {
        return jdbc.queryForObject("SELECT cpf FROM usuarios WHERE id = ?", String.class, id);
    }

    @Test
    void loginNovoGuardaOCpfSoComOsDigitos() throws Exception {
        String corpo = criar("rita@academia.com", "516.879.548-01").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(corpo, "$.id"));

        assertThat(cpfNoBanco(id)).isEqualTo(CPF);
        mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + tokenDoAdmin))
                .andExpect(jsonPath("$[?(@.email == 'rita@academia.com')].cpf").value(CPF));
    }

    @Test
    void loginNovoSemCpfOuComCpfInvalidoEhRecusado() throws Exception {
        criar("rita@academia.com", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cpf"));
        criar("rita@academia.com", "  ").andExpect(status().isBadRequest());
        criar("rita@academia.com", "516.879.548-02").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].mensagem").value("CPF inválido"));
        criar("rita@academia.com", "111.111.111-11").andExpect(status().isBadRequest());
        assertThat(repository.existsByEmail("rita@academia.com")).isFalse();
    }

    @Test
    void cpfRepetidoNaEquipeDevolve409() throws Exception {
        criar("rita@academia.com", CPF).andExpect(status().isCreated());
        criar("outra@academia.com", "516.879.548-01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("CPF já cadastrado"));
        assertThat(repository.existsByEmail("outra@academia.com")).isFalse();
    }

    @Test
    void quemEhDeAntesContinuaSemCpfAteOAdminPreencher() throws Exception {
        UUID antigo = antigoSemCpf("antigo@academia.com");

        // Renomear sem mandar o CPF continua valendo para quem não tem.
        editar(antigo, "Antigo Renomeado", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").doesNotExist());
        assertThat(cpfNoBanco(antigo)).isNull();

        editar(antigo, "Antigo Renomeado", "669.060.104-84").andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").value(OUTRO_CPF));
        assertThat(cpfNoBanco(antigo)).isEqualTo(OUTRO_CPF);
    }

    @Test
    void semCpfNoPedidoOAtualFicaECorrigirTroca() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(criar("rita@academia.com", CPF).andReturn().getResponse().getContentAsString(), "$.id"));

        editar(id, "Rita Maria", null).andExpect(status().isOk());
        assertThat(cpfNoBanco(id)).isEqualTo(CPF);
        editar(id, "Rita Maria", "").andExpect(status().isOk());
        assertThat(cpfNoBanco(id)).isEqualTo(CPF);

        editar(id, "Rita Maria", OUTRO_CPF).andExpect(status().isOk());
        assertThat(cpfNoBanco(id)).isEqualTo(OUTRO_CPF);
        editar(id, "Rita Maria", "669.060.104-85").andExpect(status().isBadRequest());
    }

    @Test
    void editarParaOCpfDeOutroDevolve409ESemMudarNada() throws Exception {
        criar("rita@academia.com", CPF).andExpect(status().isCreated());
        UUID antigo = antigoSemCpf("antigo@academia.com");

        editar(antigo, "Nome Novo", CPF).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("CPF já cadastrado"));
        assertThat(cpfNoBanco(antigo)).isNull();
        assertThat(jdbc.queryForObject("SELECT nome FROM usuarios WHERE id = ?", String.class, antigo)).isEqualTo("Antigo");
    }

    @Test
    void oCpfVaiParaATrilha() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(criar("rita@academia.com", CPF).andReturn().getResponse().getContentAsString(), "$.id"));
        editar(id, "Rita", OUTRO_CPF).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT alteracoes -> 'cpf' ->> 'depois' FROM auditoria "
                + "WHERE entidade_id = ? AND acao = 'CRIADO'", String.class, id)).isEqualTo(CPF);
        assertThat(jdbc.queryForObject("SELECT alteracoes -> 'cpf' ->> 'antes' FROM auditoria "
                + "WHERE entidade_id = ? AND acao = 'EDITADO'", String.class, id)).isEqualTo(CPF);
    }

    @Test
    void oBancoNaoAceitaCpfEmLoginDeAlunoNemForaDoFormato() {
        UUID id = antigoSemCpf("antigo@academia.com");
        assertThat(tentarNoBanco("UPDATE usuarios SET cpf = '5168795480A' WHERE id = '" + id + "'")).contains("ck_usuarios_cpf");
        assertThat(tentarNoBanco("UPDATE usuarios SET cpf = '" + CPF + "', perfil = 'ALUNO', aluno_id = NULL WHERE id = '" + id + "'"))
                .contains("ck_usuarios");
    }

    private String tentarNoBanco(String sql) {
        try {
            jdbc.update(sql);
            return "aceitou";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /**
     * Corrida (dois cadastros com o mesmo CPF ao mesmo tempo): a checagem não vê o CPF que outra
     * transação ainda não confirmou, e quem barra é o índice único. Aqui a corrida é forçada: uma
     * conexão à parte grava o CPF sem confirmar; o pedido passa pela checagem e espera no índice
     * (conferido no pg_stat_activity); a outra confirma, e o pedido tem de sair com o 409 do CPF,
     * nunca 500 nem o 409 do e-mail.
     */
    @Test
    void naCorridaDoCadastroQuemBarraEhOIndiceEDevolveO409DoCpf() throws Exception {
        MvcResult resultado = comCpfSeguradoPorOutraTransacao(() -> {
            try {
                return criar("rita@academia.com", CPF).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(resultado.getResponse().getContentAsString(), "$.title")).isEqualTo("CPF já cadastrado");
    }

    @Test
    void naCorridaDaEdicaoQuemBarraEhOIndiceEDevolveO409DoCpf() throws Exception {
        UUID antigo = antigoSemCpf("antigo@academia.com");
        MvcResult resultado = comCpfSeguradoPorOutraTransacao(() -> {
            try {
                return editar(antigo, "Antigo", CPF).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(resultado.getResponse().getContentAsString(), "$.title")).isEqualTo("CPF já cadastrado");
        assertThat(cpfNoBanco(antigo)).isNull();
    }

    private MvcResult comCpfSeguradoPorOutraTransacao(Supplier<MvcResult> pedido) throws Exception {
        try (Connection outra = dataSource.getConnection()) {
            outra.setAutoCommit(false);
            try (PreparedStatement insert = outra.prepareStatement("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, cpf) "
                    + "VALUES (?, 'Concorrente', 'concorrente@academia.com', 'x', 'RECEPCAO', ?)")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, CPF);
                insert.executeUpdate();
            }
            CompletableFuture<MvcResult> emAndamento = CompletableFuture.supplyAsync(pedido);
            esperarAlguemTravadoNoBanco();
            outra.commit();
            return emAndamento.get(10, TimeUnit.SECONDS);
        }
    }

    private void esperarAlguemTravadoNoBanco() throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(5));
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                + "AND query ILIKE '%usuarios%'", Integer.class) == 0) {
            assertThat(Instant.now()).as("o pedido não chegou a esperar no índice").isBefore(limite);
            Thread.sleep(20);
        }
    }
}
