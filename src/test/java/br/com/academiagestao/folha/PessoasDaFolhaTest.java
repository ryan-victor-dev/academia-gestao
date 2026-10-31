package br.com.academiagestao.folha;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem recebe pela folha (ADR 0049): só o ADMIN; a equipe com login aparece sozinha, e quem trabalha sem usar o
 * sistema é cadastrado só para a folha.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PessoasDaFolhaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    private final List<UUID> usuarios = new ArrayList<>();
    private final List<UUID> alunos = new ArrayList<>();
    private final List<String> pessoas = new ArrayList<>();
    private final UUID admin = UUID.randomUUID();

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'PESSOA_DA_FOLHA' AND autor = ?", admin.toString());
        jdbc.update("DELETE FROM acessos WHERE usuario_id = ?", admin);
        for (String pessoa : pessoas) {
            jdbc.update("DELETE FROM pessoas_da_folha WHERE id = ?::uuid", pessoa);
        }
        for (UUID usuario : usuarios) {
            limpeza.apagar("usuarios", usuario);
        }
        for (UUID aluno : alunos) {
            limpeza.apagar("alunos", aluno);
        }
    }

    /** Um login da equipe ou de aluno. O de aluno sai com o aluno (a limpeza leva o login junto). */
    private UUID login(String perfil, String nome, String cpf) {
        UUID id = UUID.randomUUID();
        UUID aluno = null;
        if (perfil.equals("ALUNO")) {
            aluno = UUID.randomUUID();
            jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, ?, ?)", aluno, nome, CpfsParaTeste.aleatorio());
            alunos.add(aluno);
        } else {
            usuarios.add(id);
        }
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, aluno_id, cpf) VALUES (?, ?, ?, 'x', ?, ?, ?)",
                id, nome, "folha-" + id + "@teste.local", perfil, aluno, cpf);
        return id;
    }

    private RequestPostProcessor comoAdmin() {
        return como("ADMIN", admin);
    }

    private static RequestPostProcessor como(String perfil, UUID usuario) {
        return jwt().jwt(j -> j.subject(usuario.toString()).claim("perfil", perfil))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private ResultActions incluir(String corpo) throws Exception {
        return mvc.perform(post("/api/folha/pessoas").with(comoAdmin()).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private String incluida(String corpo) throws Exception {
        String id = JsonPath.read(incluir(corpo).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        pessoas.add(id);
        return id;
    }

    private ResultActions editar(String id, String versao, String corpo) throws Exception {
        var pedido = put("/api/folha/pessoas/" + id).with(comoAdmin()).contentType(MediaType.APPLICATION_JSON).content(corpo);
        return mvc.perform(versao == null ? pedido : pedido.header("If-Match", "\"" + versao + "\""));
    }

    private ResultActions lista(String parametros) throws Exception {
        return mvc.perform(get("/api/folha/pessoas" + parametros).with(comoAdmin())).andExpect(status().isOk());
    }

    @Test
    void soOAdmin() throws Exception {
        for (String perfil : List.of("RECEPCAO", "INSTRUTOR", "ALUNO")) {
            mvc.perform(get("/api/folha/pessoas").with(como(perfil, UUID.randomUUID()))).andExpect(status().isForbidden());
            mvc.perform(post("/api/folha/pessoas").with(como(perfil, UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nome\": \"X\", \"cpf\": \"" + CpfsParaTeste.aleatorio() + "\", \"vinculo\": \"MEI\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/folha/pessoas/" + UUID.randomUUID()).with(como(perfil, UUID.randomUUID()))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"vinculo\": \"MEI\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/folha/pessoas")).andExpect(status().isUnauthorized());
    }

    @Test
    void aEquipeComLoginApareceSemVinculoEOAlunoNao() throws Exception {
        UUID instrutor = login("INSTRUTOR", "Ivo Folha", CpfsParaTeste.aleatorio());
        UUID aluno = login("ALUNO", "Ana Aluna Folha", null);

        String porLogin = "$[?(@.usuarioId == '%s')]";
        lista("").andExpect(jsonPath(porLogin.formatted(instrutor) + ".nome").value(hasItem("Ivo Folha")))
                .andExpect(jsonPath(porLogin.formatted(instrutor) + ".perfil").value(hasItem("INSTRUTOR")))
                .andExpect(jsonPath(porLogin.formatted(instrutor) + ".id").value(hasItem((Object) null)))
                .andExpect(jsonPath(porLogin.formatted(instrutor) + ".vinculo").value(hasItem((Object) null)))
                .andExpect(jsonPath(porLogin.formatted(aluno)).value(empty()));

        // O login de aluno não entra nem pedindo.
        incluir("{\"usuarioId\": \"" + aluno + "\", \"vinculo\": \"CLT\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Login da equipe não encontrado"));
        incluir("{\"usuarioId\": \"" + UUID.randomUUID() + "\", \"vinculo\": \"CLT\"}").andExpect(status().isNotFound());
    }

    @Test
    void incluiOLoginComOCombinadoUmaVezSo() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        UUID recepcao = login("RECEPCAO", "Rita Folha", cpf);

        incluir("{\"usuarioId\": \"" + recepcao + "\", \"nome\": \"Outro\", \"vinculo\": \"CLT\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("nome: quem tem login usa o nome e o CPF do login"));
        String id = idDe(incluir("{\"usuarioId\": \"" + recepcao + "\", \"vinculo\": \"CLT\", \"valorCombinado\": 2500}")
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.nome").value("Rita Folha")).andExpect(jsonPath("$.cpf").value(cpf))
                .andExpect(jsonPath("$.perfil").value("RECEPCAO")).andExpect(jsonPath("$.vinculo").value("CLT"))
                .andExpect(jsonPath("$.valorCombinado").value(2500.00)).andExpect(jsonPath("$.ativo").value(true)));
        pessoas.add(id);

        incluir("{\"usuarioId\": \"" + recepcao + "\", \"vinculo\": \"MEI\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Essa pessoa da equipe já está na folha: edite o combinado dela"));

        // Só uma linha na lista para ela, agora com o id.
        lista("").andExpect(jsonPath("$[?(@.usuarioId == '%s')].id".formatted(recepcao)).value(org.hamcrest.Matchers.contains(id)));

        Map<String, Object> trilha = jdbc.queryForMap("SELECT acao, alteracoes::text AS alteracoes FROM auditoria WHERE entidade_id = ?::uuid", id);
        assertThat(trilha.get("acao")).isEqualTo("INCLUIDA");
        assertThat((String) trilha.get("alteracoes")).contains("\"vinculo\"", "CLT", "\"valorCombinado\"", "2500.00",
                recepcao.toString());
    }

    private static String idDe(ResultActions resposta) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void quemTemLoginSoMudaOCombinado() throws Exception {
        UUID instrutor = login("INSTRUTOR", "Iara Folha", null);
        String id = incluida("{\"usuarioId\": \"" + instrutor + "\", \"vinculo\": \"MEI\", \"valorCombinado\": 1200}");

        editar(id, "0", "{\"nome\": \"Outra\", \"vinculo\": \"MEI\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("nome: o nome, o CPF e a situação de quem tem login mudam na aba Equipe"));
        editar(id, "0", "{\"vinculo\": \"MEI\", \"ativo\": false}").andExpect(status().isBadRequest());
        editar(id, "0", "{\"vinculo\": \"AUTONOMO\"}").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.vinculo").value("AUTONOMO")).andExpect(jsonPath("$.valorCombinado").doesNotExist())
                .andExpect(jsonPath("$.nome").value("Iara Folha"));

        // A situação é a do login: inativo lá, some da lista dos ativos aqui.
        jdbc.update("UPDATE usuarios SET ativo = false WHERE id = ?", instrutor);
        lista("").andExpect(jsonPath("$[*].id", not(hasItem(id))));
        lista("?incluirInativos=true").andExpect(jsonPath("$[?(@.id == '%s')].ativo".formatted(id)).value(hasItem(false)));
    }

    @Test
    void quemNaoTemLoginECadastradoSoParaAFolha() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String id = idDe(incluir("{\"nome\": \" Rosa da Limpeza \", \"cpf\": \"" + cpf + "\", \"vinculo\": \"CLT\", \"valorCombinado\": 1518}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nome").value("Rosa da Limpeza"))
                .andExpect(jsonPath("$.usuarioId").doesNotExist()).andExpect(jsonPath("$.perfil").doesNotExist()));
        pessoas.add(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios WHERE cpf = ?", Long.class, cpf)).isZero();

        incluir("{\"nome\": \"Rosa de novo\", \"cpf\": \"" + cpf + "\", \"vinculo\": \"MEI\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Esse CPF já está na folha"));
        incluir("{\"nome\": \"Sem CPF\", \"vinculo\": \"MEI\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("cpf: é obrigatório"));
        incluir("{\"nome\": \"Sem vínculo\", \"cpf\": \"" + CpfsParaTeste.aleatorio() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("vinculo: é obrigatório (CLT, MEI ou AUTONOMO)"));

        // Quem tem login entra pelo login, e não de novo pelo CPF.
        String cpfDoLogin = CpfsParaTeste.aleatorio();
        login("RECEPCAO", "Rui Folha", cpfDoLogin);
        incluir("{\"nome\": \"Rui\", \"cpf\": \"" + cpfDoLogin + "\", \"vinculo\": \"CLT\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Esse CPF é de alguém da equipe com login: inclua a pessoa pelo login"));
    }

    @Test
    void editarQuemNaoTemLoginComVersaoEInativar() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String id = incluida("{\"nome\": \"Marcos Manutenção\", \"cpf\": \"" + cpf + "\", \"vinculo\": \"AUTONOMO\"}");
        String outroCpf = CpfsParaTeste.aleatorio();
        incluida("{\"nome\": \"Outro\", \"cpf\": \"" + outroCpf + "\", \"vinculo\": \"MEI\"}");

        String corpo = "{\"nome\": \"Marcos M.\", \"cpf\": \"" + cpf + "\", \"vinculo\": \"MEI\", \"valorCombinado\": 900.5}";
        editar(id, null, corpo).andExpect(status().isPreconditionRequired());
        editar(id, "7", corpo).andExpect(status().isPreconditionFailed());
        editar(id, "0", "{\"nome\": \"Marcos\", \"cpf\": \"" + outroCpf + "\", \"vinculo\": \"MEI\"}").andExpect(status().isConflict());
        editar(id, "0", "{\"vinculo\": \"MEI\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("nome: é obrigatório"));
        editar(id, "0", corpo).andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Marcos M."))
                .andExpect(jsonPath("$.valorCombinado").value(900.50)).andExpect(jsonPath("$.ativo").value(true));

        editar(id, "1", corpo.replace("}", ", \"ativo\": false}")).andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false));
        lista("").andExpect(jsonPath("$[*].id", not(hasItem(id))));
        lista("?incluirInativos=true").andExpect(jsonPath("$[*].id", hasItem(id)));
        // Sem "ativo", fica como estava.
        editar(id, "2", corpo).andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false));

        assertThat(jdbc.queryForList("SELECT acao FROM auditoria WHERE entidade_id = ?::uuid ORDER BY id", String.class, id))
                .containsExactly("INCLUIDA", "EDITADA", "EDITADA", "EDITADA");
        mvc.perform(get("/api/folha/pessoas/" + UUID.randomUUID()).with(comoAdmin())).andExpect(status().isNotFound());
        editar(UUID.randomUUID().toString(), "0", corpo).andExpect(status().isNotFound());
    }

    /** Cada consulta (a lista e uma pessoa) vai para o registro de acessos, numa linha só, e a resposta não fica em cache. */
    @Test
    void cadaConsultaVaiParaORegistroDeAcessos() throws Exception {
        String id = incluida("{\"nome\": \"Acesso Folha\", \"cpf\": \"" + CpfsParaTeste.aleatorio() + "\", \"vinculo\": \"MEI\"}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM acessos WHERE usuario_id = ?", Long.class, admin)).isZero();

        lista("").andExpect(header().string("Cache-Control", "no-store"));
        lista("?incluirInativos=true");
        mvc.perform(get("/api/folha/pessoas/" + id).with(comoAdmin())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        // Quem não pode ver não deixa linha (o 403 vem antes).
        mvc.perform(get("/api/folha/pessoas").with(como("RECEPCAO", admin))).andExpect(status().isForbidden());

        assertThat(jdbc.queryForList("SELECT tipo || '|' || coalesce(conta, '-') || '|' || coalesce(aluno_id::text, '-') FROM acessos"
                + " WHERE usuario_id = ?", String.class, admin))
                .containsExactly("CONSULTA_FOLHA|-|-", "CONSULTA_FOLHA|-|-", "CONSULTA_FOLHA|-|-");
    }

    @Test
    void oBancoExigeQuemNaConsultaDaFolha() {
        for (String[] caso : List.of(
                new String[] {"INSERT INTO acessos (tipo, ip) VALUES ('CONSULTA_FOLHA', '127.0.0.1')", "ck_acessos_quem"},
                new String[] {"INSERT INTO acessos (tipo, usuario_id, conta, ip) VALUES ('CONSULTA_FOLHA', gen_random_uuid(), 'x', '127.0.0.1')",
                        "ck_acessos_conta"},
                new String[] {"INSERT INTO acessos (tipo, usuario_id, aluno_id, ip) VALUES ('CONSULTA_FOLHA', gen_random_uuid(), gen_random_uuid(), '127.0.0.1')",
                        "ck_acessos_consulta"})) {
            assertThatThrownBy(() -> jdbc.update(caso[0])).as(caso[1]).hasMessageContaining(caso[1]);
        }
        UUID quem = UUID.randomUUID();
        jdbc.update("INSERT INTO acessos (tipo, usuario_id, ip) VALUES ('CONSULTA_FOLHA', ?, '127.0.0.1')", quem);
        jdbc.update("DELETE FROM acessos WHERE usuario_id = ?", quem);
    }

    /** O login que está na folha tem histórico (ADR 0043): excluir dá o 409 de sempre (desative), e não 500. */
    @Test
    void oLoginNaFolhaNaoSeExclui() throws Exception {
        UUID instrutor = login("INSTRUTOR", "Exclusão Folha", null);
        incluida("{\"usuarioId\": \"" + instrutor + "\", \"vinculo\": \"CLT\"}");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/usuarios/" + instrutor)
                .with(comoAdmin())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios WHERE id = ?", Long.class, instrutor)).isEqualTo(1);
    }

    /** O banco também barra a linha que não é nem de login nem de nome com CPF (um erro da aplicação, e não do ADMIN). */
    @Test
    void oBancoQuerOLoginOuONomeComCpf() {
        UUID instrutor = login("INSTRUTOR", "Banco Folha", null);
        String inserir = "INSERT INTO pessoas_da_folha (id, usuario_id, nome, cpf, vinculo, ativo, criado_em) VALUES (?, ?, ?, ?, 'CLT', ?, now())";
        for (Object[] linha : List.of(
                new Object[] {instrutor, "Nome junto", null, true},
                new Object[] {instrutor, null, CpfsParaTeste.aleatorio(), true},
                new Object[] {instrutor, null, null, false},
                new Object[] {null, "Sem CPF", null, true},
                new Object[] {null, null, CpfsParaTeste.aleatorio(), true},
                new Object[] {null, "  ", CpfsParaTeste.aleatorio(), true},
                new Object[] {null, "CPF com pontos", "529.982.247", true})) {
            assertThatThrownBy(() -> jdbc.update(inserir, UUID.randomUUID(), linha[0], linha[1], linha[2], linha[3]))
                    .as(java.util.Arrays.toString(linha)).isInstanceOf(DataIntegrityViolationException.class);
        }
        UUID certa = UUID.randomUUID();
        jdbc.update(inserir, certa, instrutor, null, null, true);
        pessoas.add(certa.toString());
        assertThatThrownBy(() -> jdbc.update("UPDATE pessoas_da_folha SET valor_combinado = 0 WHERE id = ?", certa))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Dois pedidos ao mesmo tempo para o mesmo login: um entra, o outro recebe 409 (e não 500 do índice). */
    @Test
    void oMesmoLoginAoMesmoTempoEntraUmaVez() throws Exception {
        for (int rodada = 0; rodada < 10; rodada++) {
            UUID instrutor = login("INSTRUTOR", "Corrida " + rodada, null);
            CountDownLatch largada = new CountDownLatch(1);
            Callable<Integer> pedido = () -> {
                largada.await();
                return incluir("{\"usuarioId\": \"" + instrutor + "\", \"vinculo\": \"CLT\"}").andReturn().getResponse().getStatus();
            };
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a = threads.submit(pedido);
                Future<Integer> b = threads.submit(pedido);
                largada.countDown();
                assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 409);
            } finally {
                threads.shutdown();
            }
            pessoas.addAll(jdbc.queryForList("SELECT id::text FROM pessoas_da_folha WHERE usuario_id = ?", String.class, instrutor));
        }
    }

    /** O mesmo CPF sem login ao mesmo tempo: as duas checagens passam, e quem barra é o índice (409, e não 500). */
    @Test
    void oMesmoCpfAoMesmoTempoEntraUmaVez() throws Exception {
        for (int rodada = 0; rodada < 10; rodada++) {
            String cpf = CpfsParaTeste.aleatorio();
            CountDownLatch largada = new CountDownLatch(1);
            Callable<Integer> pedido = () -> {
                largada.await();
                return incluir("{\"nome\": \"Corrida\", \"cpf\": \"" + cpf + "\", \"vinculo\": \"MEI\"}")
                        .andReturn().getResponse().getStatus();
            };
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> a = threads.submit(pedido);
                Future<Integer> b = threads.submit(pedido);
                largada.countDown();
                assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 409);
            } finally {
                threads.shutdown();
            }
            pessoas.addAll(jdbc.queryForList("SELECT id::text FROM pessoas_da_folha WHERE cpf = ?", String.class, cpf));
        }
    }
}
