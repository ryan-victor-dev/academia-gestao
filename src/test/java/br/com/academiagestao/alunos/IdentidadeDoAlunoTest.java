package br.com.academiagestao.alunos;

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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Identidade de gênero e orientação sexual do aluno (ADR 0047, decisão 2): opcional, e só o ADMIN e o
 * próprio aluno veem. A recepção e o instrutor, não; o cadastro não traz; a trilha não guarda os valores.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IdentidadeDoAlunoTest {

    private static final String COMPLETA = """
            {"identidadeDeGenero": "MULHER_TRANS", "orientacaoSexual": "OUTRA", "orientacaoSexualOutra": " demi e pan "}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    javax.sql.DataSource dataSource;

    private final List<UUID> usuarios = new ArrayList<>();
    private UUID aluno;
    private UUID admin;

    @BeforeEach
    void cadastrar() {
        aluno = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluna Identidade', ?)", aluno, CpfsParaTeste.aleatorio());
        admin = daEquipe("ADMIN");
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM acessos WHERE aluno_id = ?", aluno);
        jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", aluno);
        limpeza.apagar("alunos", aluno);
        for (UUID usuario : usuarios) {
            jdbc.update("DELETE FROM acessos WHERE usuario_id = ?", usuario);
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

    private RequestPostProcessor oProprioAluno(String... outrasAutoridades) {
        List<GrantedAuthority> autoridades = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_ALUNO")));
        for (String outra : outrasAutoridades) {
            autoridades.add(new SimpleGrantedAuthority(outra));
        }
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("perfil", "ALUNO").claim("alunoId", aluno.toString()))
                .authorities(autoridades);
    }

    private ResultActions informar(String caminho, RequestPostProcessor quem, String corpo) throws Exception {
        return mvc.perform(put(caminho).with(quem).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    @Test
    void oAlunoRespondeVeOQueRespondeuEPodeApagar() throws Exception {
        mvc.perform(get("/api/alunos/me/identidade").with(oProprioAluno())).andExpect(status().isOk())
                .andExpect(jsonPath("$.identidadeDeGenero").doesNotExist())
                .andExpect(jsonPath("$.orientacaoSexual").doesNotExist());

        informar("/api/alunos/me/identidade", oProprioAluno(), COMPLETA).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orientacaoSexualOutra").value("demi e pan"));
        mvc.perform(get("/api/alunos/me/identidade").with(oProprioAluno())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.identidadeDeGenero").value("MULHER_TRANS"))
                .andExpect(jsonPath("$.orientacaoSexual").value("OUTRA"))
                .andExpect(jsonPath("$.orientacaoSexualOutra").value("demi e pan"));

        informar("/api/alunos/me/identidade", oProprioAluno(), "{}").andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT * FROM identidade_dos_alunos WHERE aluno_id = ?", aluno))
                .containsEntry("identidade_de_genero", null).containsEntry("orientacao_sexual", null)
                .containsEntry("orientacao_sexual_outra", null);
    }

    @Test
    void oAdminVeERegistraEAConsultaVaiParaORegistroDeAcessos() throws Exception {
        informar("/api/alunos/" + aluno + "/identidade", como("ADMIN", admin),
                "{\"identidadeDeGenero\": \"PREFIRO_NAO_INFORMAR\", \"orientacaoSexual\": \"GAY\"}").andExpect(status().isOk());

        mvc.perform(get("/api/alunos/" + aluno + "/identidade").with(como("ADMIN", admin))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.identidadeDeGenero").value("PREFIRO_NAO_INFORMAR"))
                .andExpect(jsonPath("$.orientacaoSexual").value("GAY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM acessos WHERE aluno_id = ? AND usuario_id = ? AND tipo = 'CONSULTA_ALUNO'",
                Integer.class, aluno, admin)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} {1} -> 403")
    @CsvSource({"RECEPCAO, GET", "RECEPCAO, PUT", "INSTRUTOR, GET", "INSTRUTOR, PUT", "ALUNO, GET", "ALUNO, PUT"})
    void soOAdminChegaNaIdentidadeDeOutroAluno(String perfil, String metodo) throws Exception {
        informar("/api/alunos/" + aluno + "/identidade", como("ADMIN", admin), COMPLETA).andExpect(status().isOk());
        RequestPostProcessor quem = perfil.equals("ALUNO") ? oProprioAluno() : como(perfil, daEquipe(perfil));
        String caminho = "/api/alunos/" + aluno + "/identidade";

        (metodo.equals("GET") ? mvc.perform(get(caminho).with(quem)) : informar(caminho, quem, "{}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT identidade_de_genero FROM identidade_dos_alunos WHERE aluno_id = ?",
                String.class, aluno)).isEqualTo("MULHER_TRANS");
    }

    @ParameterizedTest(name = "{0} na do aluno logado -> 403")
    @CsvSource({"ADMIN", "RECEPCAO", "INSTRUTOR"})
    void aRotaDoProprioAlunoEhSoDoAluno(String perfil) throws Exception {
        mvc.perform(get("/api/alunos/me/identidade").with(como(perfil, daEquipe(perfil)))).andExpect(status().isForbidden());
    }

    @Test
    void oCadastroDoAlunoNaoTrazAIdentidade() throws Exception {
        informar("/api/alunos/" + aluno + "/identidade", como("ADMIN", admin), COMPLETA).andExpect(status().isOk());

        for (String perfil : List.of("ADMIN", "RECEPCAO", "INSTRUTOR")) {
            String corpo = mvc.perform(get("/api/alunos/" + aluno).with(como(perfil, daEquipe(perfil))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(corpo).as(perfil).doesNotContain("MULHER_TRANS").doesNotContain("identidade").doesNotContain("orientacao");
        }
        String doAluno = mvc.perform(get("/api/alunos/me").with(oProprioAluno())).andReturn().getResponse().getContentAsString();
        assertThat(doAluno).doesNotContain("MULHER_TRANS");
    }

    @Test
    void aTrilhaRegistraQueMudouSemOsValores() throws Exception {
        informar("/api/alunos/me/identidade", oProprioAluno(), COMPLETA).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade_id = ? AND acao = 'IDENTIDADE_ALTERADA'"
                + " AND alteracoes = '{}'::jsonb", Integer.class, aluno)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade_id = ? AND (alteracoes::text LIKE '%MULHER%'"
                + " OR alteracoes::text LIKE '%demi%')", Integer.class, aluno)).isZero();
    }

    @Test
    void respostaInvalidaDevolve400ESemGravar() throws Exception {
        informar("/api/alunos/me/identidade", oProprioAluno(), "{\"identidadeDeGenero\": \"OUTRA\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("identidadeDeGeneroOutra: descreva a opção \"outra\""));
        informar("/api/alunos/me/identidade", oProprioAluno(), "{\"orientacaoSexual\": \"INEXISTENTE\"}")
                .andExpect(status().isBadRequest());
        informar("/api/alunos/me/identidade", oProprioAluno(), "{\"orientacaoSexual\": \"GAY\", \"orientacaoSexualOutra\": \"x\"}")
                .andExpect(status().isBadRequest());
        informar("/api/alunos/me/identidade", oProprioAluno(),
                "{\"identidadeDeGenero\": \"OUTRA\", \"identidadeDeGeneroOutra\": \"%s\"}".formatted("a".repeat(51)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identidade_dos_alunos WHERE aluno_id = ?", Integer.class, aluno)).isZero();
    }

    @Test
    void alunoInexistenteDevolve404() throws Exception {
        UUID outro = UUID.randomUUID();
        mvc.perform(get("/api/alunos/" + outro + "/identidade").with(como("ADMIN", admin))).andExpect(status().isNotFound());
        informar("/api/alunos/" + outro + "/identidade", como("ADMIN", admin), COMPLETA).andExpect(status().isNotFound());
    }

    @Test
    void oAlunoInativoSoDeLeituraNaoAltera() throws Exception {
        informar("/api/alunos/me/identidade", oProprioAluno("SOMENTE_LEITURA"), COMPLETA).andExpect(status().isForbidden());
        mvc.perform(get("/api/alunos/me/identidade").with(oProprioAluno("SOMENTE_LEITURA"))).andExpect(status().isOk());
    }

    @Test
    void excluirOAlunoNuncaUsadoLevaAIdentidade() throws Exception {
        informar("/api/alunos/" + aluno + "/identidade", como("ADMIN", admin), COMPLETA).andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/alunos/" + aluno)
                .with(como("ADMIN", admin))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identidade_dos_alunos WHERE aluno_id = ?", Integer.class, aluno)).isZero();
    }

    @Test
    void oBancoRecusaDescricaoSoltaMesmoSemAOpcao() {
        // Com a opção nula, "=" daria NULL no CHECK, e ele deixaria passar (armadilha do CLAUDE.md).
        assertThat(tentar("INSERT INTO identidade_dos_alunos (aluno_id, identidade_de_genero_outra, atualizado_em)"
                + " VALUES ('" + aluno + "', 'solta', now())")).contains("ck_identidade_dos_alunos_genero_outra");
        assertThat(tentar("INSERT INTO identidade_dos_alunos (aluno_id, orientacao_sexual, atualizado_em)"
                + " VALUES ('" + aluno + "', 'OUTRA', now())")).contains("ck_identidade_dos_alunos_orientacao_outra");
        assertThat(tentar("INSERT INTO identidade_dos_alunos (aluno_id, orientacao_sexual, orientacao_sexual_outra, atualizado_em)"
                + " VALUES ('" + aluno + "', 'OUTRA', ' com espaço', now())")).contains("ck_identidade_dos_alunos_orientacao_outra");
    }

    private String tentar(String sql) {
        try {
            jdbc.update(sql);
            return "aceitou";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    @Test
    void gravarEnquantoOAlunoEhExcluidoDa404ENunca500() throws Exception {
        assertThat(gravarEnquantoOutraTransacaoExclui("alunos", aluno, "/api/alunos/" + aluno + "/identidade",
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
