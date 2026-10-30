package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Identidade de gênero e orientação sexual da equipe (ADR 0047, decisão 2): só o ADMIN e a própria pessoa
 * veem; a lista da equipe não traz; o login de aluno não tem (a do aluno fica no cadastro dele).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IdentidadeDaEquipeTest {

    private static final String RESPOSTA = """
            {"identidadeDeGenero": "NAO_BINARIA", "orientacaoSexual": "BISSEXUAL"}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    javax.sql.DataSource dataSource;

    private final List<UUID> usuarios = new ArrayList<>();
    private UUID admin;
    private UUID rita;

    @BeforeEach
    void cadastrar() {
        admin = daEquipe("ADMIN");
        rita = daEquipe("RECEPCAO");
    }

    @AfterEach
    void limpar() {
        for (UUID usuario : usuarios) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ? OR autor = ?", usuario, usuario.toString());
            limpeza.apagar("usuarios", usuario);
        }
    }

    private UUID daEquipe(String perfil) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, ?, ?, 'x', ?)",
                id, perfil, "identidade-" + id + "@teste.local", perfil);
        usuarios.add(id);
        return id;
    }

    private static RequestPostProcessor como(String perfil, UUID usuario) {
        return jwt().jwt(j -> j.subject(usuario.toString()).claim("perfil", perfil))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private ResultActions informar(String caminho, RequestPostProcessor quem, String corpo) throws Exception {
        return mvc.perform(put(caminho).with(quem).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    @ParameterizedTest(name = "{0} responde a própria")
    @CsvSource({"ADMIN", "RECEPCAO", "INSTRUTOR"})
    void cadaUmRespondeEVeAPropria(String perfil) throws Exception {
        UUID eu = daEquipe(perfil);
        mvc.perform(get("/api/me/identidade").with(como(perfil, eu))).andExpect(status().isOk())
                .andExpect(jsonPath("$.orientacaoSexual").doesNotExist());

        informar("/api/me/identidade", como(perfil, eu), RESPOSTA).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/me/identidade").with(como(perfil, eu))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.identidadeDeGenero").value("NAO_BINARIA"))
                .andExpect(jsonPath("$.orientacaoSexual").value("BISSEXUAL"));
        assertThat(jdbc.queryForObject("SELECT orientacao_sexual FROM identidade_da_equipe WHERE usuario_id = ?",
                String.class, eu)).isEqualTo("BISSEXUAL");
    }

    @Test
    void oAdminVeERegistraADeOutro() throws Exception {
        informar("/api/usuarios/" + rita + "/identidade", como("ADMIN", admin), RESPOSTA).andExpect(status().isOk());

        mvc.perform(get("/api/usuarios/" + rita + "/identidade").with(como("ADMIN", admin))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.identidadeDeGenero").value("NAO_BINARIA"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade_id = ? AND acao = 'IDENTIDADE_ALTERADA'"
                + " AND alteracoes = '{}'::jsonb", Integer.class, rita)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} {1} na de outro -> 403")
    @CsvSource({"RECEPCAO, GET", "RECEPCAO, PUT", "INSTRUTOR, GET", "INSTRUTOR, PUT"})
    void soOAdminChegaNaDeOutro(String perfil, String metodo) throws Exception {
        String caminho = "/api/usuarios/" + rita + "/identidade";
        RequestPostProcessor quem = como(perfil, daEquipe(perfil));
        (metodo.equals("GET") ? mvc.perform(get(caminho).with(quem)) : informar(caminho, quem, RESPOSTA))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identidade_da_equipe WHERE usuario_id = ?", Integer.class, rita)).isZero();
    }

    @Test
    void oAlunoNaoUsaARotaDaEquipeEOLoginDeAlunoNaoTem() throws Exception {
        UUID aluno = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, br.com.academiagestao.alunos.CpfsParaTeste.aleatorio());
        UUID login = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, aluno_id) VALUES (?, 'Aluno', ?, 'x', 'ALUNO', ?)",
                login, "identidade-" + login + "@teste.local", aluno);
        try {
            mvc.perform(get("/api/me/identidade").with(como("ALUNO", login))).andExpect(status().isForbidden());
            informar("/api/me/identidade", como("ALUNO", login), RESPOSTA).andExpect(status().isForbidden());
            mvc.perform(get("/api/usuarios/" + login + "/identidade").with(como("ADMIN", admin))).andExpect(status().isNotFound());
            informar("/api/usuarios/" + login + "/identidade", como("ADMIN", admin), RESPOSTA).andExpect(status().isNotFound());
            mvc.perform(get("/api/usuarios/" + UUID.randomUUID() + "/identidade").with(como("ADMIN", admin)))
                    .andExpect(status().isNotFound());
        } finally {
            limpeza.apagar("alunos", aluno);
        }
    }

    @Test
    void aListaDaEquipeNaoTrazAIdentidade() throws Exception {
        informar("/api/usuarios/" + rita + "/identidade", como("ADMIN", admin), RESPOSTA).andExpect(status().isOk());

        String corpo = mvc.perform(get("/api/usuarios").with(como("ADMIN", admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("BISSEXUAL").doesNotContain("orientacao");
        String meu = mvc.perform(get("/api/me").with(como("RECEPCAO", rita))).andReturn().getResponse().getContentAsString();
        assertThat(meu).doesNotContain("BISSEXUAL");
    }

    @Test
    void excluirOLoginNuncaUsadoLevaAIdentidade() throws Exception {
        UUID novo = daEquipe("INSTRUTOR");
        jdbc.update("INSERT INTO identidade_da_equipe (usuario_id, orientacao_sexual, atualizado_em) VALUES (?, 'GAY', now())", novo);

        mvc.perform(delete("/api/usuarios/" + novo).with(como("ADMIN", admin))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identidade_da_equipe WHERE usuario_id = ?", Integer.class, novo)).isZero();
    }

    @Test
    void gravarEnquantoOLoginEhExcluidoDa404ENunca500() throws Exception {
        UUID novo = daEquipe("INSTRUTOR");
        assertThat(gravarEnquantoOutraTransacaoExclui("usuarios", novo, "/api/usuarios/" + novo + "/identidade",
                como("ADMIN", admin))).isEqualTo(404);
    }

    /**
     * Corrida com a exclusão do cadastro (ADR 0043): outra transação apaga {@code tabela} sem confirmar; o
     * pedido tem de esperar na trava (conferido no pg_stat_activity) e, com a exclusão confirmada, sair
     * com 404. Sem o FOR SHARE, ele leria o cadastro ainda lá e a chave estrangeira daria 500.
     */
    private int gravarEnquantoOutraTransacaoExclui(String tabela, UUID id, String caminho, RequestPostProcessor quem)
            throws Exception {
        try (java.sql.Connection outra = dataSource.getConnection()) {
            outra.setAutoCommit(false);
            try (java.sql.PreparedStatement apagar = outra.prepareStatement("DELETE FROM " + tabela + " WHERE id = ?")) {
                apagar.setObject(1, id);
                assertThat(apagar.executeUpdate()).isEqualTo(1);
            }
            java.util.concurrent.CompletableFuture<Integer> pedido = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return informar(caminho, quem, "{\"orientacaoSexual\": \"GAY\"}").andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            java.time.Instant limite = java.time.Instant.now().plusSeconds(5);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                    + " AND query ILIKE '%FOR SHARE%'", Integer.class) == 0) {
                assertThat(java.time.Instant.now()).as("o pedido não chegou a esperar na trava").isBefore(limite);
                Thread.sleep(20);
            }
            outra.commit();
            return pedido.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
