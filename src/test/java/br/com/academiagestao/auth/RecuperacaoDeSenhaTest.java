package br.com.academiagestao.auth;

import br.com.academiagestao.CorreioDeTeste;
import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Esqueci a senha" (decisões do dono, ADR 0038): alunos e equipe, menos o ADMIN, com o e-mail
 * confirmado. Nada revela se o e-mail existe. Cada teste usa um IP próprio (limites do login).
 */
@Import({TestcontainersConfiguration.class, CorreioDeTeste.Config.class})
// Os limites do login baixos, como no LimiteDaTrocaDeSenhaTest: aqui, cada teste erra no máximo uma vez por
// conta e faz no máximo 10 requisições do seu IP.
@SpringBootTest(properties = {"academia.login.falhas-por-conta-e-ip=3", "academia.login.tentativas-por-ip=15"})
@AutoConfigureMockMvc
class RecuperacaoDeSenhaTest {

    private static final String SENHA = "senha-de-teste-123";
    private static final String NOVA = "senha-nova-bem-forte-456";

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
    CorreioDeTeste correio;

    private final List<UUID> usuarios = new ArrayList<>();
    private final String ip = "10.77." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256);

    @AfterEach
    void limpar() {
        correio.falhar(false);
        for (UUID id : usuarios) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", id);
            limpeza.apagar("usuarios", id);
        }
        jdbc.update("DELETE FROM tentativas_login WHERE chave LIKE 'rec-%' OR chave = ?", ip);
    }

    private RequestPostProcessor doIp() {
        return requisicao -> {
            requisicao.setRemoteAddr(ip);
            return requisicao;
        };
    }

    private String email() {
        return "rec-" + UUID.randomUUID() + "@teste.local";
    }

    private Usuario usuario(Perfil perfil, String email, boolean confirmado) {
        Usuario usuario = repository.save(Usuario.daEquipe("Equipe", email, passwordEncoder.encode(SENHA), perfil));
        usuarios.add(usuario.getId());
        if (confirmado) {
            jdbc.update("UPDATE usuarios SET email_confirmado_em = now() WHERE id = ?", usuario.getId());
        }
        return usuario;
    }

    private ResultActions pedir(String email) throws Exception {
        return mvc.perform(post("/api/auth/esqueci-senha").with(doIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\"}"));
    }

    private ResultActions redefinir(String email, String codigo, String nova) throws Exception {
        return mvc.perform(post("/api/auth/redefinir-senha").with(doIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"codigo\": \"" + codigo + "\", \"novaSenha\": \"" + nova + "\"}"));
    }

    private ResultActions login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").with(doIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"senha\": \"" + senha + "\"}"));
    }

    /** O e-mail sai em segundo plano: espera até 5 s. */
    private Optional<String> codigoQueChegou(String email) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Optional<String> codigo = correio.ultimoCodigo(email);
            if (codigo.isPresent()) {
                return codigo;
            }
            Thread.sleep(100);
        }
        return Optional.empty();
    }

    /** Dá tempo ao envio em segundo plano antes de conferir que nada saiu. */
    private void nadaChegou(String email) throws InterruptedException {
        Thread.sleep(500);
        assertThat(correio.para(email)).isEmpty();
    }

    @Test
    void ligadoATelaOferece() throws Exception {
        mvc.perform(get("/api/auth/opcoes")).andExpect(status().isOk()).andExpect(jsonPath("$.esqueciSenha").value(true));
    }

    /** O código troca a senha: a antiga para de valer, e as sessões abertas caem. */
    @Test
    void codigoTrocaASenhaEDerrubaAsSessoes() throws Exception {
        String email = email();
        Usuario recepcao = usuario(Perfil.RECEPCAO, email, true);
        String tokenAntigo = JsonPath.read(login(email, SENHA).andReturn().getResponse().getContentAsString(), "$.token");

        pedir(email.toUpperCase()).andExpect(status().isAccepted());
        String codigo = codigoQueChegou(email).orElseThrow();
        assertThat(correio.para(email).getLast().assunto()).isEqualTo("Código para criar uma nova senha");
        String errado = codigo.equals("000000") ? "111111" : "000000";
        redefinir(email, errado, NOVA).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Código inválido"));
        redefinir(email, codigo, NOVA).andExpect(status().isNoContent());

        login(email, SENHA).andExpect(status().isUnauthorized());
        login(email, NOVA).andExpect(status().isOk());
        mvc.perform(get("/api/planos").header("Authorization", "Bearer " + tokenAntigo))
                .andExpect(status().isUnauthorized());
        redefinir(email, codigo, "outra-senha-bem-forte-789").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade_id = ? AND acao = 'SENHA_RECUPERADA'",
                Integer.class, recepcao.getId())).isEqualTo(1);
    }

    /**
     * Quem não pode (sem confirmar, ADMIN, inativo, inexistente) recebe a mesma resposta, e
     * nada sai: a tela não revela quem está cadastrado.
     */
    @Test
    void quemNaoPodeRecebeAMesmaRespostaENadaSai() throws Exception {
        String semConfirmar = email();
        String admin = email();
        String inativo = email();
        String inexistente = email();
        usuario(Perfil.RECEPCAO, semConfirmar, false);
        usuario(Perfil.ADMIN, admin, true);
        Usuario desligado = usuario(Perfil.INSTRUTOR, inativo, true);
        jdbc.update("UPDATE usuarios SET ativo = false WHERE id = ?", desligado.getId());
        String confirmado = email();
        usuario(Perfil.INSTRUTOR, confirmado, true);
        String respostaDeQuemPode = pedir(confirmado).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        for (String email : List.of(semConfirmar, admin, inativo, inexistente)) {
            pedir(email).andExpect(status().isAccepted()).andExpect(content().string(respostaDeQuemPode));
            nadaChegou(email);
            redefinir(email, "123456", NOVA).andExpect(status().isBadRequest());
        }
        login(admin, SENHA).andExpect(status().isOk());
    }

    /** A senha nova segue a política (12 caracteres), antes de olhar o código. */
    @Test
    void senhaNovaSegueAPolitica() throws Exception {
        String email = email();
        usuario(Perfil.RECEPCAO, email, true);
        pedir(email).andExpect(status().isAccepted());
        String codigo = codigoQueChegou(email).orElseThrow();

        redefinir(email, codigo, "curta").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("12")));

        redefinir(email, codigo, NOVA).andExpect(status().isNoContent());
    }

    /** Pedir de novo em menos de 1 minuto: a mesma resposta, mas nenhum e-mail novo. */
    @Test
    void pedirDeNovoCedoNaoMandaOutro() throws Exception {
        String email = email();
        usuario(Perfil.RECEPCAO, email, true);
        pedir(email).andExpect(status().isAccepted());
        codigoQueChegou(email).orElseThrow();

        pedir(email).andExpect(status().isAccepted());

        Thread.sleep(500);
        assertThat(correio.para(email)).hasSize(1);
    }

    /** Código errado conta como senha errada: no limite do login (3 aqui), trava (429), também o login. */
    @Test
    void codigoErradoContaNoLimiteDoLogin() throws Exception {
        String email = email();
        usuario(Perfil.RECEPCAO, email, true);
        pedir(email).andExpect(status().isAccepted());
        codigoQueChegou(email).orElseThrow();

        for (int i = 0; i < 3; i++) {
            redefinir(email, "000001", NOVA).andExpect(status().isBadRequest());
        }

        redefinir(email, "000001", NOVA).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        login(email, SENHA).andExpect(status().isTooManyRequests());
    }

    /** Pedidos demais do mesmo IP: o limite do login (15 aqui) vale também para quem não existe. */
    @Test
    void pedidosDemaisDoMesmoIp() throws Exception {
        for (int i = 0; i < 15; i++) {
            pedir(email()).andExpect(status().isAccepted());
        }

        pedir(email()).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    /** Servidor de e-mail fora do ar: a resposta não muda (não revela a conta), e fica no log. */
    @Test
    void correioForaDoArNaoMudaAResposta() throws Exception {
        String email = email();
        usuario(Perfil.RECEPCAO, email, true);
        correio.falhar(true);

        pedir(email).andExpect(status().isAccepted());

        nadaChegou(email);
    }
}
