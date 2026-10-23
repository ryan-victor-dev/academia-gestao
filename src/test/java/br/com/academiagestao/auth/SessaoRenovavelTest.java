package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sessão renovável (ADR 0033): o cookie do login troca o token vencido por um novo até o prazo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SessaoRenovavelTest {

    private static final String SENHA = "senha-da-sessao-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    Sessoes sessoes;

    @Autowired
    LimpezaDoBanco limpeza;

    private final String email = "sessao-" + UUID.randomUUID() + "@teste.local";
    private Usuario usuario;

    @BeforeEach
    void cadastrar() {
        usuario = repository.save(Usuario.daEquipe("Sessão", email, passwordEncoder.encode(SENHA), Perfil.RECEPCAO));
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        jdbc.update("DELETE FROM tentativas_login WHERE chave = ?", email);
        jdbc.update("DELETE FROM tentativas_login WHERE momento > now() + interval '1 minute'");
        jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", usuario.getId());
        limpeza.apagar("usuarios", usuario.getId());
    }

    private MockHttpServletResponse entrar() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private ResultActions renovar(String cookie) throws Exception {
        return mvc.perform(post("/api/auth/renovar").cookie(new Cookie(Sessoes.COOKIE, cookie)));
    }

    private static String cookieDa(MockHttpServletResponse resposta) {
        Cookie cookie = resposta.getCookie(Sessoes.COOKIE);
        assertThat(cookie).as("Set-Cookie da sessão").isNotNull();
        return cookie.getValue();
    }

    private void usarToken(String corpo, int esperado) throws Exception {
        String token = JsonPath.read(corpo, "$.token");
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(esperado));
    }

    private void mudarNoBanco(Consumer<Usuario> mudanca) {
        Usuario atual = repository.findById(usuario.getId()).orElseThrow();
        mudanca.accept(atual);
        repository.save(atual);
    }

    @Test
    void loginAbreASessaoNumCookieQueScriptNenhumLe() throws Exception {
        MockHttpServletResponse resposta = entrar();

        String setCookie = resposta.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith(Sessoes.COOKIE + "=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=43200");
        Instant sessaoExpiraEm = Instant.parse(JsonPath.read(resposta.getContentAsString(), "$.sessaoExpiraEm"));
        assertThat(sessaoExpiraEm).isCloseTo(Instant.now().plus(Duration.ofHours(12)), within(Duration.ofMinutes(1)));
        // Só o hash fica no banco, nunca o segredo do cookie.
        byte[] guardado = jdbc.queryForObject("SELECT segredo_hash FROM sessoes WHERE usuario_id = ?",
                byte[].class, usuario.getId());
        String segredo = cookieDa(resposta).substring(cookieDa(resposta).indexOf('.') + 1);
        assertThat(guardado).isNotEqualTo(java.util.Base64.getUrlDecoder().decode(segredo));
    }

    @Test
    void renovarDevolveTokenQueFuncionaETrocaOSegredo() throws Exception {
        String cookie = cookieDa(entrar());

        MockHttpServletResponse renovada = renovar(cookie)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andReturn().getResponse();

        usarToken(renovada.getContentAsString(), 200);
        String novo = cookieDa(renovada);
        assertThat(novo).isNotEqualTo(cookie);
        renovar(novo).andExpect(status().isOk());
    }

    @Test
    void duasAbasRenovandoJuntasNaoDerrubamASessao() throws Exception {
        relogio.parar();
        String cookie = cookieDa(entrar());
        String novo = cookieDa(renovar(cookie).andExpect(status().isOk()).andReturn().getResponse());

        // A outra aba chega com o segredo anterior dentro da folga: token sim, cookie novo não.
        relogio.adiantar(Duration.ofSeconds(29));
        MockHttpServletResponse atrasada = renovar(cookie).andExpect(status().isOk()).andReturn().getResponse();

        assertThat(atrasada.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        usarToken(atrasada.getContentAsString(), 200);
        renovar(novo).andExpect(status().isOk());
    }

    @Test
    void segredoReaproveitadoForaDaFolgaEncerraASessao() throws Exception {
        relogio.parar();
        String cookie = cookieDa(entrar());
        String novo = cookieDa(renovar(cookie).andExpect(status().isOk()).andReturn().getResponse());

        relogio.adiantar(Duration.ofSeconds(31));
        renovar(cookie)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // Quem tinha o cookie novo (o dono ou quem copiou) também perde: entra de novo.
        renovar(novo).andExpect(status().isUnauthorized());
    }

    @Test
    void sairEncerraASessaoEApagaOCookie() throws Exception {
        String cookie = cookieDa(entrar());

        mvc.perform(post("/api/auth/sair").cookie(new Cookie(Sessoes.COOKIE, cookie)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        renovar(cookie).andExpect(status().isUnauthorized());
        // Sair de novo, ou sem cookie, não é erro.
        mvc.perform(post("/api/auth/sair").cookie(new Cookie(Sessoes.COOKIE, cookie)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/sair")).andExpect(status().isNoContent());
    }

    @Test
    void tokenVencidoNoCabecalhoNaoAtrapalhaRenovarNemSair() throws Exception {
        String cookie = cookieDa(entrar());

        String novo = cookieDa(mvc.perform(post("/api/auth/renovar").cookie(new Cookie(Sessoes.COOKIE, cookie))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token.vencido.ou-invalido"))
                .andExpect(status().isOk())
                .andReturn().getResponse());
        mvc.perform(post("/api/auth/sair").cookie(new Cookie(Sessoes.COOKIE, novo))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token.vencido.ou-invalido"))
                .andExpect(status().isNoContent());
        renovar(novo).andExpect(status().isUnauthorized());
        // No resto da API, o cabeçalho continua valendo.
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer token.vencido.ou-invalido"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sairComSegredoErradoNaoEncerraASessaoDeOutro() throws Exception {
        String cookie = cookieDa(entrar());
        String id = cookie.substring(0, cookie.indexOf('.'));

        mvc.perform(post("/api/auth/sair").cookie(new Cookie(Sessoes.COOKIE,
                        id + "." + "A".repeat(43))))
                .andExpect(status().isNoContent());

        renovar(cookie).andExpect(status().isOk());
    }

    @Test
    void prazoContaDoLoginERenovarNaoEstica() throws Exception {
        relogio.parar();
        MockHttpServletResponse login = entrar();
        String cookie = cookieDa(login);
        Instant fim = Instant.parse(JsonPath.read(login.getContentAsString(), "$.sessaoExpiraEm"));

        // Perto do fim, o token não passa do fim da sessão.
        relogio.adiantar(Duration.ofHours(12).minusMinutes(5));
        MockHttpServletResponse quaseNoFim = renovar(cookie).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(Instant.parse(JsonPath.read(quaseNoFim.getContentAsString(), "$.expiraEm"))).isEqualTo(fim);
        assertThat(quaseNoFim.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=300");

        relogio.adiantar(Duration.ofMinutes(5));
        renovar(cookieDa(quaseNoFim)).andExpect(status().isUnauthorized());
    }

    @Test
    void trocarOuRedefinirASenhaOuDesativarDerrubaASessao() throws Exception {
        String cookie = cookieDa(entrar());
        mudarNoBanco(u -> u.redefinirComSenhaTemporaria(passwordEncoder.encode("outra-senha-123")));
        renovar(cookie).andExpect(status().isUnauthorized());

        mudarNoBanco(u -> u.trocarSenha(passwordEncoder.encode(SENHA)));
        String outro = cookieDa(entrar());
        mudarNoBanco(Usuario::desativar);
        renovar(outro).andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioDesativadoDiretoNoBancoNaoRenova() throws Exception {
        String cookie = cookieDa(entrar());
        // Sem passar pela entidade, a versão do token não muda: só a conferência do "ativo" pega.
        jdbc.update("UPDATE usuarios SET ativo = false WHERE id = ?", usuario.getId());

        renovar(cookie).andExpect(status().isUnauthorized());
    }

    @Test
    void trocarASenhaAbreUmaSessaoNova() throws Exception {
        mudarNoBanco(u -> u.redefinirComSenhaTemporaria(passwordEncoder.encode(SENHA)));
        MockHttpServletResponse login = entrar();
        String cookieAntigo = cookieDa(login);
        String token = JsonPath.read(login.getContentAsString(), "$.token");

        MockHttpServletResponse troca = mvc.perform(post("/api/auth/trocar-senha")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\": \"%s\", \"novaSenha\": \"nova-senha-da-sessao-456\"}".formatted(SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        renovar(cookieAntigo).andExpect(status().isUnauthorized());
        MockHttpServletResponse renovada = renovar(cookieDa(troca)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat((Boolean) JsonPath.read(renovada.getContentAsString(), "$.precisaTrocarSenha")).isFalse();
    }

    @Test
    void cookieAusenteOuInvalidoRecebeOMesmo401() throws Exception {
        String cookie = cookieDa(entrar());
        String id = cookie.substring(0, cookie.indexOf('.'));
        // Base64 válido com o id certo, mas curto: recusado sem chegar a derrubar a sessão.
        List<String> invalidos = List.of("lixo", "lixo.lixo", id + ".curto", id + ".AAAA",
                UUID.randomUUID() + cookie.substring(cookie.indexOf('.')));

        String semCookie = mvc.perform(post("/api/auth/renovar"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andReturn().getResponse().getContentAsString();
        for (String invalido : invalidos) {
            String corpo = renovar(invalido).andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();
            assertThat(corpo).as(invalido).isEqualTo(semCookie);
        }
        // Nenhuma das tentativas derrubou a sessão de verdade.
        renovar(cookie).andExpect(status().isOk());
    }

    @Test
    void limpezaApagaSoAsVencidasOuEncerradasHaMaisDeUmDia() throws Exception {
        String ativa = cookieDa(entrar());
        String encerrada = cookieDa(entrar());
        mvc.perform(post("/api/auth/sair").cookie(new Cookie(Sessoes.COOKIE, encerrada)));
        // Vencida há 12 horas: ainda fica (pode ser útil ver uma sessão que acabou de vencer).
        Instant antes = Instant.now().minus(Duration.ofDays(1));
        jdbc.update("""
                INSERT INTO sessoes (id, usuario_id, segredo_hash, versao_token, criada_em, renovada_em, expira_em)
                VALUES (?, ?, ?, 0, ?, ?, ?)""", UUID.randomUUID(), usuario.getId(), new byte[32],
                Timestamp.from(antes), Timestamp.from(antes), Timestamp.from(antes.plus(Duration.ofHours(12))));

        sessoes.limparAntigas();
        assertThat(contarSessoes()).isEqualTo(3);

        relogio.parar();
        // Um dia depois: a vencida e a encerrada saem; a ativa, vencida há menos de um dia, fica.
        relogio.adiantar(Duration.ofDays(1).plusMinutes(1));
        sessoes.limparAntigas();

        assertThat(contarSessoes()).isEqualTo(1);
        relogio.restaurar();
        renovar(ativa).andExpect(status().isOk());
    }

    private int contarSessoes() {
        return jdbc.queryForObject("SELECT count(*) FROM sessoes WHERE usuario_id = ?", Integer.class, usuario.getId());
    }

    /**
     * Renovações simultâneas com o mesmo cookie: uma troca o segredo, a outra cai na folga. Sem
     * a trava da linha, as duas trocariam, e uma aba ficaria com um segredo que logo derruba a
     * sessão. Várias rodadas: uma só pode passar por sorte.
     */
    @Test
    void renovacoesSimultaneasTrocamOSegredoUmaVezSo() throws Exception {
        for (int rodada = 0; rodada < 15; rodada++) {
            String cookie = cookieDa(entrar());
            CountDownLatch largada = new CountDownLatch(1);
            List<CompletableFuture<MockHttpServletResponse>> abas = new ArrayList<>();
            for (int aba = 0; aba < 4; aba++) {
                abas.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        largada.await();
                        return renovar(cookie).andReturn().getResponse();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            largada.countDown();

            List<MockHttpServletResponse> respostas = abas.stream().map(CompletableFuture::join).toList();
            assertThat(respostas).as("rodada %d", rodada).allMatch(r -> r.getStatus() == 200);
            assertThat(respostas.stream().filter(r -> r.getHeader(HttpHeaders.SET_COOKIE) != null))
                    .as("rodada %d", rodada).hasSize(1);
        }
    }
}
