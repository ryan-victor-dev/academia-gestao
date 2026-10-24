package br.com.academiagestao.auth;

import br.com.academiagestao.CorreioDeTeste;
import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Primeiro acesso do aluno (decisões do dono, ADR 0039): o aluno ativo sem login cria a senha com
 * o CPF, o e-mail do cadastro e o código que chega nele. Nada revela quem é aluno. Cada teste usa
 * um IP próprio (limites do login).
 */
@Import({TestcontainersConfiguration.class, CorreioDeTeste.Config.class})
// Os limites do login baixos, como no RecuperacaoDeSenhaTest: aqui, cada teste erra no máximo duas vezes por
// conta (menos o do limite) e faz no máximo 20 requisições do seu IP.
@SpringBootTest(properties = {"academia.login.falhas-por-conta-e-ip=3", "academia.login.tentativas-por-ip=20"})
@AutoConfigureMockMvc
class PrimeiroAcessoTest {

    private static final String SENHA = "senha-do-aluno-123";

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

    private final List<UUID> alunos = new ArrayList<>();
    private final List<UUID> usuarios = new ArrayList<>();
    private final String ip = "10.78." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256);

    @AfterEach
    void limpar() {
        correio.falhar(false);
        for (UUID id : alunos) {
            repository.findByAlunoId(id).ifPresent(u -> jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", u.getId()));
            limpeza.apagar("alunos", id);
        }
        for (UUID id : usuarios) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", id);
            limpeza.apagar("usuarios", id);
        }
        jdbc.update("DELETE FROM tentativas_login WHERE chave LIKE 'pa-%' OR chave = ?", ip);
    }

    private RequestPostProcessor doIp() {
        return requisicao -> {
            requisicao.setRemoteAddr(ip);
            return requisicao;
        };
    }

    private static String email() {
        return "pa-" + UUID.randomUUID() + "@teste.local";
    }

    /** Um aluno no cadastro, sem login. */
    private UUID aluno(String cpf, String email, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email, ativo) VALUES (?, 'Aluna Nova', ?, ?, ?)", id, cpf, email, ativo);
        alunos.add(id);
        return id;
    }

    private static String formatado(String cpf) {
        return cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9);
    }

    private ResultActions pedir(String cpf, String email) throws Exception {
        return mvc.perform(post("/api/auth/primeiro-acesso").with(doIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cpf\": \"" + cpf + "\", \"email\": \"" + email + "\"}"));
    }

    private ResultActions criarSenha(String cpf, String email, String codigo, String senha) throws Exception {
        return mvc.perform(post("/api/auth/primeiro-acesso/senha").with(doIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cpf\": \"" + cpf + "\", \"email\": \"" + email + "\", \"codigo\": \"" + codigo
                        + "\", \"senha\": \"" + senha + "\"}"));
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
        mvc.perform(get("/api/auth/opcoes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.primeiroAcesso").value(true));
    }

    /**
     * O código cria o login com a senha escolhida, ligado ao cadastro e com o e-mail já
     * confirmado: o aluno entra sem trocar a senha nem confirmar o e-mail de novo.
     */
    @Test
    void codigoCriaOLoginComOEmailConfirmado() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String email = email();
        UUID alunoId = aluno(cpf, email, true);

        pedir(formatado(cpf), email.toUpperCase()).andExpect(status().isAccepted());
        String codigo = codigoQueChegou(email).orElseThrow();
        assertThat(correio.para(email).getLast().assunto()).isEqualTo("Código para criar o seu acesso");
        String errado = codigo.equals("000000") ? "111111" : "000000";
        criarSenha(cpf, email, errado, SENHA).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Código inválido"));
        assertThat(repository.findByAlunoId(alunoId)).isEmpty();
        criarSenha(cpf, email, codigo, SENHA).andExpect(status().isNoContent());

        Usuario usuario = repository.findByAlunoId(alunoId).orElseThrow();
        assertThat(usuario.getPerfil()).isEqualTo(Perfil.ALUNO);
        assertThat(usuario.getEmail()).isEqualTo(email);
        assertThat(usuario.getEmailConfirmadoEm()).isNotNull();
        login(email, SENHA).andExpect(status().isOk())
                .andExpect(jsonPath("$.precisaTrocarSenha").value(false))
                .andExpect(jsonPath("$.precisaConfirmarEmail").value(false));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auditoria WHERE entidade_id = ? AND acao = 'CRIADO_NO_PRIMEIRO_ACESSO'",
                Integer.class, usuario.getId())).isEqualTo(1);
        // Uso único: com o login criado, nem o mesmo código serve de novo.
        criarSenha(cpf, email, codigo, "outra-senha-bem-forte-789").andExpect(status().isBadRequest());
        login(email, SENHA).andExpect(status().isOk());
    }

    /**
     * Quem não pode (inativo, já tem login, e-mail ou CPF que não batem, CPF inválido, sem e-mail
     * no cadastro, e-mail de outro login) recebe a mesma resposta, e nada sai.
     */
    @Test
    void quemNaoPodeRecebeAMesmaRespostaENadaSai() throws Exception {
        String cpfQuePode = CpfsParaTeste.aleatorio();
        String emailQuePode = email();
        aluno(cpfQuePode, emailQuePode, true);
        String respostaDeQuemPode = pedir(cpfQuePode, emailQuePode).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String cpfInativo = CpfsParaTeste.aleatorio();
        String emailInativo = email();
        aluno(cpfInativo, emailInativo, false);
        String cpfComLogin = CpfsParaTeste.aleatorio();
        String emailComLogin = email();
        UUID comLogin = aluno(cpfComLogin, emailComLogin, true);
        usuarios.add(repository.save(Usuario.deAluno("Aluna", emailComLogin, passwordEncoder.encode(SENHA), comLogin)).getId());
        // O login ficou com outro e-mail (a recepção mudou o do cadastro depois): já tem login do mesmo jeito.
        String cpfLoginComOutroEmail = CpfsParaTeste.aleatorio();
        String emailDoCadastro = email();
        UUID comLoginDeOutroEmail = aluno(cpfLoginComOutroEmail, emailDoCadastro, true);
        usuarios.add(repository.save(Usuario.deAluno("Aluna", email(), passwordEncoder.encode(SENHA),
                comLoginDeOutroEmail)).getId());
        String cpfOutroEmail = CpfsParaTeste.aleatorio();
        aluno(cpfOutroEmail, email(), true);
        String cpfSemEmail = CpfsParaTeste.aleatorio();
        aluno(cpfSemEmail, null, true);
        // O e-mail do cadastro já é de um login da equipe: o login do aluno não poderia ser criado.
        String cpfEmailDaEquipe = CpfsParaTeste.aleatorio();
        String emailDaEquipe = email();
        aluno(cpfEmailDaEquipe, emailDaEquipe, true);
        usuarios.add(repository.save(Usuario.daEquipe("Equipe", emailDaEquipe, passwordEncoder.encode(SENHA),
                Perfil.RECEPCAO)).getId());

        List<String[]> casos = List.of(
                new String[] {cpfInativo, emailInativo},
                new String[] {cpfComLogin, emailComLogin},
                new String[] {cpfLoginComOutroEmail, emailDoCadastro},
                new String[] {cpfOutroEmail, email()},
                new String[] {CpfsParaTeste.aleatorio(), emailQuePode},
                new String[] {"12345678900", emailQuePode},
                new String[] {cpfSemEmail, email()},
                new String[] {cpfEmailDaEquipe, emailDaEquipe});
        for (String[] caso : casos) {
            pedir(caso[0], caso[1]).andExpect(status().isAccepted()).andExpect(content().string(respostaDeQuemPode));
            nadaChegou(caso[1].equals(emailQuePode) ? "ninguem@teste.local" : caso[1]);
            criarSenha(caso[0], caso[1], "123456", SENHA).andExpect(status().isBadRequest());
        }
        // Só o pedido de quem pode mandou e-mail.
        assertThat(correio.para(emailQuePode)).hasSize(1);
    }

    /** Cadastro antigo, com o e-mail em maiúsculas: bate do mesmo jeito, e o login sai em minúsculas. */
    @Test
    void emailDoCadastroEmMaiusculasBate() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String email = email();
        UUID alunoId = aluno(cpf, email.toUpperCase(), true);

        pedir(cpf, email).andExpect(status().isAccepted());
        String codigo = codigoQueChegou(email).orElseThrow();
        criarSenha(cpf, email, codigo, SENHA).andExpect(status().isNoContent());

        assertThat(repository.findByAlunoId(alunoId).orElseThrow().getEmail()).isEqualTo(email);
    }

    /** O código vai para o e-mail do cadastro: se a recepção muda o e-mail depois, ele não serve mais. */
    @Test
    void codigoNaoServeSeOEmailDoCadastroMudou() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String email = email();
        UUID alunoId = aluno(cpf, email, true);
        pedir(cpf, email).andExpect(status().isAccepted());
        String codigo = codigoQueChegou(email).orElseThrow();
        String novo = email();
        jdbc.update("UPDATE alunos SET email = ? WHERE id = ?", novo, alunoId);

        criarSenha(cpf, email, codigo, SENHA).andExpect(status().isBadRequest());
        criarSenha(cpf, novo, codigo, SENHA).andExpect(status().isBadRequest());

        assertThat(repository.findByAlunoId(alunoId)).isEmpty();
    }

    /** A senha segue a política (12 caracteres), antes de olhar o código. */
    @Test
    void senhaSegueAPolitica() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String email = email();
        aluno(cpf, email, true);
        pedir(cpf, email).andExpect(status().isAccepted());
        String codigo = codigoQueChegou(email).orElseThrow();

        criarSenha(cpf, email, codigo, "curta").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("12")));

        criarSenha(cpf, email, codigo, SENHA).andExpect(status().isNoContent());
    }

    /** Pedir de novo em menos de 1 minuto: a mesma resposta, mas nenhum e-mail novo. */
    @Test
    void pedirDeNovoCedoNaoMandaOutro() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String email = email();
        aluno(cpf, email, true);
        pedir(cpf, email).andExpect(status().isAccepted());
        codigoQueChegou(email).orElseThrow();

        pedir(cpf, email).andExpect(status().isAccepted());

        Thread.sleep(500);
        assertThat(correio.para(email)).hasSize(1);
    }

    /** Código errado conta como senha errada: no limite do login (3 aqui), trava (429). */
    @Test
    void codigoErradoContaNoLimiteDoLogin() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String email = email();
        aluno(cpf, email, true);
        pedir(cpf, email).andExpect(status().isAccepted());
        String codigo = codigoQueChegou(email).orElseThrow();
        String errado = codigo.equals("000001") ? "000002" : "000001";

        for (int i = 0; i < 3; i++) {
            criarSenha(cpf, email, errado, SENHA).andExpect(status().isBadRequest());
        }

        criarSenha(cpf, email, codigo, SENHA).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    /** Pedidos demais do mesmo IP: o limite do login (20 aqui) vale também para quem não é aluno. */
    @Test
    void pedidosDemaisDoMesmoIp() throws Exception {
        for (int i = 0; i < 20; i++) {
            pedir(CpfsParaTeste.aleatorio(), email()).andExpect(status().isAccepted());
        }

        pedir(CpfsParaTeste.aleatorio(), email()).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    /** Servidor de e-mail fora do ar: a resposta não muda (não revela o cadastro), e fica no log. */
    @Test
    void correioForaDoArNaoMudaAResposta() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String email = email();
        aluno(cpf, email, true);
        correio.falhar(true);

        pedir(cpf, email).andExpect(status().isAccepted());

        nadaChegou(email);
    }

    /**
     * Dois pedidos ao mesmo tempo para o mesmo aluno: os dois 202, um e-mail só (a trava pelo
     * aluno faz o segundo esperar e cair no intervalo de 1 minuto). Várias rodadas.
     */
    @Test
    void doisPedidosAoMesmoTempoMandamUmCodigo() throws Exception {
        for (int rodada = 0; rodada < 8; rodada++) {
            String cpf = CpfsParaTeste.aleatorio();
            String email = email();
            aluno(cpf, email, true);
            CountDownLatch largada = new CountDownLatch(1);
            List<CompletableFuture<Integer>> pedidos = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                pedidos.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        largada.await();
                        return pedir(cpf, email).andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            largada.countDown();

            for (CompletableFuture<Integer> pedido : pedidos) {
                assertThat(pedido.get()).as("rodada %d", rodada).isEqualTo(202);
            }
            codigoQueChegou(email).orElseThrow();
            Thread.sleep(300);
            assertThat(correio.para(email)).as("rodada %d", rodada).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM codigos_de_email WHERE email = ?", Integer.class, email))
                    .isEqualTo(1);
        }
    }

    /** CPF maior que "000.000.000-00": 400, antes de qualquer consulta. */
    @Test
    void cpfGrandeDemaisERecusado() throws Exception {
        pedir("123.456.789-0099", email()).andExpect(status().isBadRequest());
    }

    /** Sem login, só as duas rotas: o resto do caminho continua fechado. */
    @Test
    void semLoginSoEstasRotas() throws Exception {
        mvc.perform(post("/api/auth/primeiro-acesso/outra").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
