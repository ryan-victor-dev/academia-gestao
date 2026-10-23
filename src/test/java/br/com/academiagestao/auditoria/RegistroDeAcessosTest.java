package br.com.academiagestao.auditoria;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.RegistroDeAcessos;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O registro de acessos (ADR 0031): quem da equipe abriu o cadastro de qual aluno, e os
 * logins com a conta e o IP. O bloqueio pelo limite é testado no LimiteDeLoginTest (com os
 * limites de verdade); a retenção de 1 ano, no RetencaoDaAuditoriaTest.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RegistroDeAcessosTest {

    private static final String SENHA = "senha-de-teste-acessos-1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RegistroDeAcessos registro;

    @Autowired
    PlatformTransactionManager transacoes;

    private final UUID recepcao = UUID.randomUUID();
    private final UUID instrutor = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID aluno = UUID.randomUUID();
    private final String sufixo = UUID.randomUUID().toString().substring(0, 8);
    /** Um IP só deste teste: os registros dele não se confundem com os de outros. */
    private final String ip = "10.77." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);

    @BeforeEach
    void preparar() {
        String hash = passwordEncoder.encode(SENHA);
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Recepção', ?, ?, 'RECEPCAO')",
                recepcao, email("recepcao"), hash);
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Instrutor', ?, ?, 'INSTRUTOR')",
                instrutor, email("instrutor"), hash);
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Admin', ?, ?, 'ADMIN')",
                admin, email("admin"), hash);
        jdbc.update("INSERT INTO alunos (id, nome, cpf, observacoes) VALUES (?, 'Aluno', ?, 'Lesão no joelho')", aluno,
                CpfsParaTeste.aleatorio());
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM acessos WHERE ip = ? OR usuario_id IN (?, ?, ?) OR aluno_id = ?", ip, recepcao,
                instrutor, admin, aluno);
        limpeza.apagar("alunos", aluno);
        for (UUID usuario : List.of(recepcao, instrutor, admin)) {
            limpeza.apagar("usuarios", usuario);
        }
    }

    private String email(String quem) {
        return quem + "-" + sufixo + "@acessos.local";
    }

    private RequestPostProcessor como(String perfil, UUID id) {
        RequestPostProcessor token = jwt().jwt(j -> j.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
        return requisicao -> {
            requisicao.setRemoteAddr(ip);
            return token.postProcessRequest(requisicao);
        };
    }

    private ResultActions login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, senha)));
    }

    private List<Map<String, Object>> acessos() {
        return jdbc.queryForList("SELECT tipo, usuario_id, conta, ip, aluno_id FROM acessos WHERE ip = ? ORDER BY id", ip);
    }

    /** Quem da equipe abriu o cadastro: qualquer perfil, inclusive o instrutor (vê as observações). */
    @Test
    void consultaAoCadastroEntraComQuemEOIp() throws Exception {
        for (var quem : List.of(Map.entry("RECEPCAO", recepcao), Map.entry("INSTRUTOR", instrutor),
                Map.entry("ADMIN", admin))) {
            mvc.perform(get("/api/alunos/" + aluno).with(como(quem.getKey(), quem.getValue())))
                    .andExpect(status().isOk());
        }

        assertThat(acessos()).extracting(a -> a.get("tipo")).containsOnly("CONSULTA_ALUNO");
        assertThat(acessos()).extracting(a -> a.get("usuario_id")).containsExactly(recepcao, instrutor, admin);
        assertThat(acessos()).extracting(a -> a.get("aluno_id")).containsOnly(aluno);
        assertThat(acessos()).extracting(a -> a.get("conta")).containsOnly((Object) null);
    }

    /** Buscas e listas não entram (seria uma linha por aluno listado); nem o 404. */
    @Test
    void listaBuscaEInexistenteNaoEntram() throws Exception {
        mvc.perform(get("/api/alunos").param("busca", "Aluno").with(como("RECEPCAO", recepcao)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/alunos/" + UUID.randomUUID()).with(como("RECEPCAO", recepcao)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/alunos/" + aluno).with(como("ALUNO", UUID.randomUUID())))
                .andExpect(status().isForbidden());

        assertThat(acessos()).isEmpty();
    }

    @Test
    void loginCertoErradoEContaQueNaoExiste() throws Exception {
        login(email("recepcao"), SENHA).andExpect(status().isOk());
        login(email("recepcao"), "senha-errada-xxxx").andExpect(status().isUnauthorized());
        // O e-mail digitado fica, normalizado, mesmo sem conta (decisão do dono).
        login("Ninguem-" + sufixo + "@Acessos.LOCAL", "senha-errada-xxxx").andExpect(status().isUnauthorized());
        // Inativo: recusado como senha errada, com o id da conta.
        jdbc.update("UPDATE usuarios SET ativo = false WHERE id = ?", instrutor);
        login(email("instrutor"), SENHA).andExpect(status().isUnauthorized());

        List<Map<String, Object>> acessos = acessos();
        assertThat(acessos).extracting(a -> a.get("tipo"))
                .containsExactly("LOGIN", "LOGIN_FALHO", "LOGIN_FALHO", "LOGIN_FALHO");
        assertThat(acessos).extracting(a -> a.get("usuario_id")).containsExactly(recepcao, recepcao, null, instrutor);
        assertThat(acessos).extracting(a -> a.get("conta")).containsExactly(email("recepcao"), email("recepcao"),
                "ninguem-" + sufixo + "@acessos.local", email("instrutor"));
        assertThat(acessos).extracting(a -> a.get("aluno_id")).containsOnly((Object) null);
        // Nunca a senha.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM acessos WHERE ip = ? AND acessos::text LIKE '%senha%'",
                Integer.class, ip)).isZero();
    }

    /** Senha atual errada na troca de senha (decisão do dono): pode ser um token roubado. */
    @Test
    void senhaAtualErradaNaTroca() throws Exception {
        String corpo = login(email("admin"), SENHA).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String token = JsonPath.read(corpo, "$.token");

        mvc.perform(post("/api/auth/trocar-senha").with(r -> { r.setRemoteAddr(ip); return r; })
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\": \"chute-errado-123\", \"novaSenha\": \"nova-senha-forte-123\"}"))
                .andExpect(status().isBadRequest());

        assertThat(acessos()).extracting(a -> a.get("tipo")).containsExactly("LOGIN", "TROCA_SENHA_FALHA");
        assertThat(acessos().get(1).get("usuario_id")).isEqualTo(admin);
        assertThat(acessos().get(1).get("conta")).isEqualTo(email("admin"));
    }

    @Test
    void adminConsultaComFiltros() throws Exception {
        mvc.perform(get("/api/alunos/" + aluno).with(como("INSTRUTOR", instrutor))).andExpect(status().isOk());
        login(email("recepcao"), "senha-errada-xxxx").andExpect(status().isUnauthorized());
        login(email("recepcao"), SENHA).andExpect(status().isOk());

        mvc.perform(get("/api/auditoria/acessos").param("alunoId", aluno.toString()).with(como("ADMIN", admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(1))
                .andExpect(jsonPath("$.conteudo[0].tipo").value("CONSULTA_ALUNO"))
                .andExpect(jsonPath("$.conteudo[0].usuarioId").value(instrutor.toString()))
                .andExpect(jsonPath("$.conteudo[0].usuarioNome").value("Instrutor"))
                .andExpect(jsonPath("$.conteudo[0].ip").value(ip));
        // A conta como foi digitada no filtro: o mesmo jeito do login.
        mvc.perform(get("/api/auditoria/acessos").param("conta", " " + email("recepcao").toUpperCase() + " ")
                        .with(como("ADMIN", admin)))
                .andExpect(jsonPath("$.conteudo[*].tipo").value(org.hamcrest.Matchers.contains("LOGIN", "LOGIN_FALHO")));
        mvc.perform(get("/api/auditoria/acessos").param("conta", email("recepcao")).param("tipo", "LOGIN_FALHO")
                        .with(como("ADMIN", admin)))
                .andExpect(jsonPath("$.conteudo", hasSize(1)));
        mvc.perform(get("/api/auditoria/acessos").param("usuarioId", recepcao.toString()).param("tamanho", "1")
                        .with(como("ADMIN", admin)))
                .andExpect(jsonPath("$.conteudo", hasSize(1)))
                .andExpect(jsonPath("$.conteudo[0].tipo").value("LOGIN"))
                .andExpect(jsonPath("$.totalElementos").value(2))
                .andExpect(jsonPath("$.totalPaginas").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEPCAO", "INSTRUTOR", "ALUNO"})
    void soOAdminConsulta(String perfil) throws Exception {
        mvc.perform(get("/api/auditoria/acessos").with(como(perfil, UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void filtrosInvalidos400() throws Exception {
        for (String[] parametro : List.of(new String[] {"tipo", "OUTRO"}, new String[] {"tamanho", "101"},
                new String[] {"pagina", "-1"}, new String[] {"alunoId", "x"})) {
            mvc.perform(get("/api/auditoria/acessos").param(parametro[0], parametro[1]).with(como("ADMIN", admin)))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * Dentro de uma transação, o registro seria desfeito com ela (a do login com senha errada
     * é desfeita): a implementação recusa, e quem chama descobre na hora.
     */
    @Test
    void recusaGravarDentroDeUmaTransacao() {
        TransactionTemplate transacao = new TransactionTemplate(transacoes);
        assertThatThrownBy(() -> transacao.executeWithoutResult(
                t -> registro.consultaDeAluno(recepcao, aluno, ip))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transacao.executeWithoutResult(
                t -> registro.login(RegistroDeAcessos.Tipo.LOGIN, recepcao, "x@y.z", ip)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transacao.executeWithoutResult(
                t -> registro.loginBloqueado(recepcao, "x@y.z", ip, Duration.ofMinutes(15))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> registro.login(RegistroDeAcessos.Tipo.CONSULTA_ALUNO, recepcao, "x@y.z", ip))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registro.login(RegistroDeAcessos.Tipo.LOGIN_BLOQUEADO, recepcao, "x@y.z", ip))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(acessos()).isEmpty();
    }

    /**
     * Um ataque de horas não vira uma linha por tentativa: o bloqueio entra uma vez por conta e
     * IP dentro da janela (diagnóstico da etapa 8, M3).
     */
    @Test
    void bloqueioUmaVezPorContaEIpNaJanela() {
        Duration janela = Duration.ofMinutes(15);
        String conta = email("atacada");
        for (int i = 0; i < 5; i++) {
            registro.loginBloqueado(null, conta, ip, janela);
        }
        assertThat(acessos()).hasSize(1);

        // Outra conta do mesmo IP, ou a mesma conta de outro IP: outro ataque, outra linha.
        registro.loginBloqueado(null, email("outra"), ip, janela);
        registro.loginBloqueado(null, conta, ip + "0", janela);
        assertThat(jdbc.update("DELETE FROM acessos WHERE ip = ?", ip + "0")).isEqualTo(1);
        assertThat(acessos()).hasSize(2);

        // Ainda dentro da janela (14 min atrás): nada novo. Fora dela (16 min): uma linha nova.
        jdbc.update("UPDATE acessos SET momento = now() - interval '14 minutes' WHERE ip = ?", ip);
        registro.loginBloqueado(null, conta, ip, janela);
        assertThat(acessos()).hasSize(2);
        jdbc.update("UPDATE acessos SET momento = now() - interval '16 minutes' WHERE ip = ?", ip);
        registro.loginBloqueado(null, conta, ip, janela);
        assertThat(acessos()).hasSize(3);
        assertThat(acessos()).extracting(a -> a.get("tipo")).containsOnly("LOGIN_BLOQUEADO");
    }

    /** As travas da V38, também para quem grava sem passar pelo Java. */
    @Test
    void bancoRecusaAcessoIncoerente() {
        String insere = "INSERT INTO acessos (tipo, usuario_id, conta, ip, aluno_id) VALUES (?, ?, ?, ?, ?)";
        for (Object[] linha : List.<Object[]>of(
                new Object[] {"CONSULTA_ALUNO", recepcao, null, ip, null, "ck_acessos_consulta"},
                new Object[] {"LOGIN", recepcao, "a@b.c", ip, aluno, "ck_acessos_consulta"},
                new Object[] {"CONSULTA_ALUNO", null, null, ip, aluno, "ck_acessos_quem"},
                new Object[] {"CONSULTA_ALUNO", recepcao, "a@b.c", ip, aluno, "ck_acessos_conta"},
                new Object[] {"LOGIN", recepcao, null, ip, null, "ck_acessos_conta"},
                new Object[] {"TROCA_SENHA_FALHA", null, "a@b.c", ip, null, "ck_acessos_troca"},
                new Object[] {"OUTRO", recepcao, "a@b.c", ip, null, "acessos_tipo_check"},
                new Object[] {"LOGIN", recepcao, "a@b.c", null, null, "ip"})) {
            assertThatThrownBy(() -> jdbc.update(insere, linha[0], linha[1], linha[2], linha[3], linha[4]))
                    .as((String) linha[5]).hasMessageContaining((String) linha[5]);
        }
        jdbc.update(insere, "LOGIN_FALHO", null, "a@b.c", ip, null);
        jdbc.update(insere, "LOGIN_BLOQUEADO", null, "a@b.c", ip, null);
        assertThat(acessos()).hasSize(2);
    }
}
