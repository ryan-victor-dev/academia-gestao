package br.com.academiagestao.auth;

import br.com.academiagestao.CorreioDeTeste;
import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A confirmação do e-mail no primeiro acesso (decisões do dono, ADR 0038), com login de
 * verdade (o token simulado pularia as regras) e o e-mail ligado por um correio de teste.
 */
@Import({TestcontainersConfiguration.class, CorreioDeTeste.Config.class})
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmacaoDeEmailTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    @Autowired
    CorreioDeTeste correio;

    private final List<UUID> usuarios = new ArrayList<>();
    private final List<UUID> alunos = new ArrayList<>();

    @AfterEach
    void limpar() {
        relogio.restaurar();
        correio.falhar(false);
        for (UUID id : usuarios) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", id);
            limpeza.apagar("usuarios", id);
        }
        for (UUID id : alunos) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", id);
            limpeza.apagar("alunos", id);
        }
    }

    private String email() {
        return "conf-" + UUID.randomUUID() + "@teste.local";
    }

    private Usuario daEquipe(Perfil perfil, String email) {
        Usuario usuario = repository.save(Usuario.daEquipe("Equipe", email, passwordEncoder.encode(SENHA), perfil));
        usuarios.add(usuario.getId());
        return usuario;
    }

    private Usuario aluno(String email) {
        UUID alunoId = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email) VALUES (?, 'Aluna', ?, ?)", alunoId,
                CpfsParaTeste.aleatorio(), email);
        alunos.add(alunoId);
        Usuario usuario = repository.save(Usuario.deAluno("Aluna", email, passwordEncoder.encode(SENHA), alunoId));
        usuarios.add(usuario.getId());
        return usuario;
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"senha\": \"" + SENHA + "\"}"));
    }

    private String token(String email) throws Exception {
        return JsonPath.read(login(email).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.token");
    }

    private ResultActions codigo(String token) throws Exception {
        return mvc.perform(post("/api/auth/email/codigo").header("Authorization", "Bearer " + token));
    }

    private ResultActions confirmar(String token, String codigo) throws Exception {
        return mvc.perform(post("/api/auth/email/confirmar").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"codigo\": \"" + codigo + "\"}"));
    }

    private ResultActions corrigir(String token, String novo) throws Exception {
        return mvc.perform(put("/api/auth/email").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + novo + "\"}"));
    }

    private void adiantar(Duration quanto) {
        relogio.fixar(Instant.now().plus(quanto));
    }

    /** Sem confirmar, só /api/me e as rotas do e-mail; confirmado, o resto abre. */
    @Test
    void primeiroAcessoPedeAConfirmacao() throws Exception {
        String email = email();
        Usuario recepcao = daEquipe(Perfil.RECEPCAO, email);

        login(email).andExpect(jsonPath("$.precisaConfirmarEmail").value(true))
                .andExpect(jsonPath("$.precisaTrocarSenha").value(false));
        String token = token(email);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaConfirmarEmail").value(true))
                .andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/planos").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Confirmação de e-mail obrigatória"));
        // A regra geral, e não só o perfil tirado do token: barra até rota que não exige perfil (ou não existe).
        mvc.perform(get("/api/rota-que-nao-existe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        codigo(token).andExpect(status().isNoContent());
        String codigo = correio.ultimoCodigo(email).orElseThrow();
        assertThat(correio.para(email).getLast().texto()).contains("vale 15 minutos");
        String errado = codigo.equals("000000") ? "111111" : "000000";
        confirmar(token, errado).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Código inválido"));
        confirmar(token, codigo).andExpect(status().isNoContent());

        assertThat(repository.findById(recepcao.getId()).orElseThrow().getEmailConfirmadoEm()).isNotNull();
        String novo = token(email);
        login(email).andExpect(jsonPath("$.precisaConfirmarEmail").value(false));
        mvc.perform(get("/api/planos").header("Authorization", "Bearer " + novo)).andExpect(status().isOk());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + novo))
                .andExpect(jsonPath("$.precisaConfirmarEmail").value(false));
        codigo(novo).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade_id = ? AND acao = 'EMAIL_CONFIRMADO'",
                Integer.class, recepcao.getId())).isEqualTo(1);
    }

    /** O ADMIN não confirma (decisão do dono): entra direto. */
    @Test
    void adminNaoPrecisa() throws Exception {
        String email = email();
        daEquipe(Perfil.ADMIN, email);

        login(email).andExpect(jsonPath("$.precisaConfirmarEmail").value(false));
        mvc.perform(get("/api/planos").header("Authorization", "Bearer " + token(email))).andExpect(status().isOk());
    }

    /** O aluno também confirma; e a senha temporária vem antes do e-mail. */
    @Test
    void senhaTemporariaVemAntes() throws Exception {
        String email = email();
        Usuario aluno = aluno(email);
        jdbc.update("UPDATE usuarios SET precisa_trocar_senha = true WHERE id = ?", aluno.getId());

        login(email).andExpect(jsonPath("$.precisaTrocarSenha").value(true))
                .andExpect(jsonPath("$.precisaConfirmarEmail").value(false));
        String temporario = token(email);
        codigo(temporario).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Troca de senha obrigatória"));

        String corpo = mvc.perform(post("/api/auth/trocar-senha").header("Authorization", "Bearer " + temporario)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\": \"" + SENHA + "\", \"novaSenha\": \"outra-senha-forte-456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaConfirmarEmail").value(true))
                .andReturn().getResponse().getContentAsString();
        String depois = JsonPath.read(corpo, "$.token");
        mvc.perform(get("/api/cobrancas/me").header("Authorization", "Bearer " + depois))
                .andExpect(status().isForbidden());
        codigo(depois).andExpect(status().isNoContent());
    }

    /** Um código por minuto, no máximo 5 por hora; o novo encerra o anterior. */
    @Test
    void limiteDeCodigosEONovoEncerraOAnterior() throws Exception {
        String email = email();
        daEquipe(Perfil.INSTRUTOR, email);
        String token = token(email);

        codigo(token).andExpect(status().isNoContent());
        String primeiro = correio.ultimoCodigo(email).orElseThrow();
        codigo(token).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        for (int i = 1; i <= 4; i++) {
            adiantar(Duration.ofSeconds(61L * i));
            codigo(token).andExpect(status().isNoContent());
        }
        adiantar(Duration.ofSeconds(61L * 5));
        codigo(token).andExpect(status().isTooManyRequests());
        assertThat(correio.para(email)).hasSize(5);

        String ultimo = correio.ultimoCodigo(email).orElseThrow();
        if (!primeiro.equals(ultimo)) {
            confirmar(token, primeiro).andExpect(status().isBadRequest());
        }
        confirmar(token, ultimo).andExpect(status().isNoContent());
    }

    /** Cinco tentativas erradas esgotam o código: nem o certo vale mais. */
    @Test
    void tentativasEsgotamOCodigo() throws Exception {
        String email = email();
        daEquipe(Perfil.RECEPCAO, email);
        String token = token(email);
        codigo(token).andExpect(status().isNoContent());
        String codigo = correio.ultimoCodigo(email).orElseThrow();
        String errado = codigo.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            confirmar(token, errado).andExpect(status().isBadRequest());
        }

        confirmar(token, codigo).andExpect(status().isBadRequest());
    }

    /** Vale 15 minutos. */
    @Test
    void codigoVence() throws Exception {
        String email = email();
        daEquipe(Perfil.RECEPCAO, email);
        String token = token(email);
        codigo(token).andExpect(status().isNoContent());
        String codigo = correio.ultimoCodigo(email).orElseThrow();

        adiantar(Duration.ofMinutes(15).plusSeconds(1));
        String tokenDepois = token(email);

        confirmar(tokenDepois, codigo).andExpect(status().isBadRequest());
    }

    /** E-mail errado: o aluno corrige; o código vai para o novo, e o cadastro acompanha. */
    @Test
    void alunoCorrigeOEmail() throws Exception {
        String errado = email();
        Usuario aluno = aluno(errado);
        String token = token(errado);
        codigo(token).andExpect(status().isNoContent());
        String codigoDoErrado = correio.ultimoCodigo(errado).orElseThrow();
        String certo = email();

        adiantar(Duration.ofSeconds(61));
        corrigir(token, certo.toUpperCase()).andExpect(status().isNoContent());

        assertThat(repository.findById(aluno.getId()).orElseThrow().getEmail()).isEqualTo(certo);
        assertThat(jdbc.queryForObject("SELECT email FROM alunos WHERE id = ?", String.class, aluno.getAlunoId()))
                .isEqualTo(certo);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade_id = ? AND acao = 'EMAIL_CORRIGIDO'",
                Integer.class, aluno.getAlunoId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade_id = ? AND acao = 'EMAIL_CORRIGIDO'",
                Integer.class, aluno.getId())).isEqualTo(1);
        String tokenNovo = token(certo);
        confirmar(tokenNovo, codigoDoErrado).andExpect(status().isBadRequest());
        confirmar(tokenNovo, correio.ultimoCodigo(certo).orElseThrow()).andExpect(status().isNoContent());
        corrigir(token(certo), email()).andExpect(status().isConflict());
    }

    /** O e-mail de outro login não serve, e não muda nada. */
    @Test
    void naoCorrigeParaOEmailDeOutro() throws Exception {
        String email = email();
        String outro = email();
        Usuario aluno = aluno(email);
        daEquipe(Perfil.RECEPCAO, outro);

        corrigir(token(email), outro).andExpect(status().isConflict());

        assertThat(repository.findById(aluno.getId()).orElseThrow().getEmail()).isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT email FROM alunos WHERE id = ?", String.class, aluno.getAlunoId()))
                .isEqualTo(email);
    }

    /** Servidor de e-mail fora do ar: 503, e dá para pedir de novo quando voltar. */
    @Test
    void correioForaDoAr() throws Exception {
        String email = email();
        daEquipe(Perfil.RECEPCAO, email);
        String token = token(email);
        correio.falhar(true);

        codigo(token).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Não foi possível enviar o e-mail agora. Tente de novo em alguns minutos."));

        correio.falhar(false);
        adiantar(Duration.ofSeconds(61));
        codigo(token).andExpect(status().isNoContent());
        assertThat(correio.ultimoCodigo(email)).isPresent();
    }

    /**
     * Dois pedidos de código ao mesmo tempo (dois toques): um manda, o outro espera o minuto
     * (429), nunca erro. A trava da linha do usuário põe um depois do outro. Várias rodadas.
     */
    @Test
    void doisPedidosDeCodigoAoMesmoTempo() throws Exception {
        for (int rodada = 0; rodada < 10; rodada++) {
            String email = email();
            daEquipe(Perfil.RECEPCAO, email);
            String token = token(email);
            java.util.concurrent.CountDownLatch largada = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.CompletableFuture<Integer>> pedidos = List.of(1, 2).stream()
                    .map(i -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            largada.await();
                            return codigo(token).andReturn().getResponse().getStatus();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })).toList();
            largada.countDown();

            assertThat(pedidos.stream().map(java.util.concurrent.CompletableFuture::join).toList())
                    .as("rodada %d", rodada).containsExactlyInAnyOrder(204, 429);
            assertThat(correio.para(email)).as("rodada %d", rodada).hasSize(1);
        }
    }
}
