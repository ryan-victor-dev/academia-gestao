package br.com.academiagestao.alunos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.auth.Perfil;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inativar o aluno deixa o login dele só de leitura (ADR 0029): ele continua vendo os
 * próprios dados, mas só consulta. Reativar volta ao normal. Com logins de verdade: o token
 * simulado pula a validação no banco.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InativacaoComLoginTest {

    private static final String SENHA = "senha-da-ana-123";
    private static final RequestPostProcessor RECEPCAO =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));

    @Autowired
    LimpezaDoBanco limpeza;

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
        limpeza.apagarTudo();
        ana = alunoRepository.save(Aluno.novo(new DadosAluno("Ana", "52998224725", "ana@email.com", null, null, null), Instant.now()));
        usuarioRepository.save(Usuario.deAluno("Ana", "ana@email.com", passwordEncoder.encode(SENHA), ana.getId()));
    }

    private ResultActions loginDaAna() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"ana@email.com\", \"senha\": \"%s\"}".formatted(SENHA)));
    }

    private String tokenDaAna() throws Exception {
        return JsonPath.read(loginDaAna().andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.token");
    }

    private ResultActions comToken(String token, MockHttpServletRequestBuilder pedido) throws Exception {
        return mvc.perform(pedido.header("Authorization", "Bearer " + token));
    }

    @Test
    void inativoEntraEVeOsPropriosDados() throws Exception {
        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO)).andExpect(status().isOk());

        String token = tokenDaAna();
        comToken(token, get("/api/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.somenteLeitura").value(true));
        comToken(token, get("/api/alunos/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
        for (String rota : new String[] {"/api/matriculas/me", "/api/cobrancas/me", "/api/fichas/me", "/api/planos"}) {
            comToken(token, get(rota)).andExpect(status().isOk());
        }
        Usuario login = usuarioRepository.findByAlunoId(ana.getId()).orElseThrow();
        assertThat(login.isAtivo()).isTrue();
        assertThat(login.isSomenteLeitura()).isTrue();
        // A restrição vai no token: é dele que a cadeia de segurança tira o SOMENTE_LEITURA.
        String carga = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat((Boolean) JsonPath.read(carga, "$.somenteLeitura")).isTrue();
    }

    @Test
    void inativoTrocaAPropriaSenhaEContinuaSoDeLeitura() throws Exception {
        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO));

        String corpo = comToken(tokenDaAna(), post("/api/auth/trocar-senha").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\": \"%s\", \"novaSenha\": \"outra-senha-da-ana-456\"}".formatted(SENHA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        comToken(JsonPath.read(corpo, "$.token"), get("/api/me")).andExpect(jsonPath("$.somenteLeitura").value(true));
    }

    @Test
    void tokenDeAntesDaInativacaoMorreNaHora() throws Exception {
        String token = tokenDaAna();
        comToken(token, get("/api/alunos/me")).andExpect(status().isOk());

        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO));

        comToken(token, get("/api/alunos/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void reativarVoltaAoNormalEDerrubaOTokenSoDeLeitura() throws Exception {
        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO));
        String soLeitura = tokenDaAna();

        mvc.perform(post("/api/alunos/" + ana.getId() + "/reativar").with(RECEPCAO)).andExpect(status().isOk());

        comToken(soLeitura, get("/api/alunos/me")).andExpect(status().isUnauthorized());
        comToken(tokenDaAna(), get("/api/me")).andExpect(jsonPath("$.somenteLeitura").value(false));
        assertThat(usuarioRepository.findByAlunoId(ana.getId()).orElseThrow().isSomenteLeitura()).isFalse();
    }

    @Test
    void inativacaoEReativacaoFicamNaTrilhaDoLogin() throws Exception {
        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO));
        mvc.perform(post("/api/alunos/" + ana.getId() + "/reativar").with(RECEPCAO));

        UUID login = usuarioRepository.findByAlunoId(ana.getId()).orElseThrow().getId();
        assertThat(jdbc.queryForList("SELECT acao FROM auditoria WHERE entidade = 'USUARIO' AND entidade_id = ? "
                + "ORDER BY momento, id", String.class, login)).containsExactly("SOMENTE_LEITURA", "LIBERADO");
        assertThat(jdbc.queryForObject("SELECT alteracoes::text FROM auditoria WHERE entidade = 'USUARIO' "
                + "AND entidade_id = ? AND acao = 'SOMENTE_LEITURA'", String.class, login)).contains("somenteLeitura");
    }

    @Test
    void alunoSemLoginTambemPodeSerInativadoEReativado() throws Exception {
        Aluno semLogin = alunoRepository.save(Aluno.novo(new DadosAluno("Bia", "11144477735", null, null, null, null), Instant.now()));

        mvc.perform(post("/api/alunos/" + semLogin.getId() + "/inativar").with(RECEPCAO)).andExpect(status().isOk());
        mvc.perform(post("/api/alunos/" + semLogin.getId() + "/reativar").with(RECEPCAO)).andExpect(status().isOk());
    }

    @Test
    void inativarUmAlunoNaoMexeNoLoginDeOutros() throws Exception {
        usuarioRepository.save(Usuario.daEquipe("Rita", "rita@academia.com", "hash", Perfil.RECEPCAO));
        Aluno bia = alunoRepository.save(Aluno.novo(new DadosAluno("Bia", "11144477735", null, null, null, null), Instant.now()));

        mvc.perform(post("/api/alunos/" + bia.getId() + "/inativar").with(RECEPCAO));

        assertThat(usuarioRepository.findByAlunoId(ana.getId()).orElseThrow().isSomenteLeitura()).isFalse();
        assertThat(usuarioRepository.findByEmail("rita@academia.com").orElseThrow().isAtivo()).isTrue();
    }
}
