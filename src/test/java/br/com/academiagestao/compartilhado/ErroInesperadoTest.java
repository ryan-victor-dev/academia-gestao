package br.com.academiagestao.compartilhado;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uma exceção que nenhum tratador pega sai em problem+json, como o resto da API, e sem a
 * mensagem dela (revisão de 2026-10-17, B1). Com o servidor de verdade: o MockMvc não passa
 * pela página de erro do servlet.
 */
@Import({TestcontainersConfiguration.class, ErroInesperadoTest.Rota.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class ErroInesperadoTest {

    private static final String SENHA = "senha-do-erro-inesperado-1";

    @TestConfiguration
    @RestController
    static class Rota {
        @GetMapping("/api/teste/erro-inesperado")
        String falhar() {
            throw new IllegalStateException("segredo-interno: tabela xyz");
        }
    }

    @LocalServerPort
    int porta;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    LimpezaDoBanco limpeza;

    private final UUID admin = UUID.randomUUID();

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM acessos WHERE usuario_id = ?", admin);
        limpeza.apagar("usuarios", admin);
    }

    @Test
    void erroInesperadoSaiEmProblemJsonSemAMensagem() throws Exception {
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Admin', ?, ?, 'ADMIN')",
                admin, admin + "@teste.local", passwordEncoder.encode(SENHA));
        HttpClient cliente = HttpClient.newHttpClient();
        HttpResponse<String> login = cliente.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta
                        + "/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\": \"%s@teste.local\", \"senha\": \"%s\"}"
                        .formatted(admin, SENHA))).build(), HttpResponse.BodyHandlers.ofString());
        String token = JsonPath.read(login.body(), "$.token");

        HttpResponse<String> resposta = cliente.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta
                        + "/api/teste/erro-inesperado")).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resposta.statusCode()).isEqualTo(500);
        assertThat(resposta.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat((String) JsonPath.read(resposta.body(), "$.title")).isEqualTo("Erro interno");
        assertThat((Integer) JsonPath.read(resposta.body(), "$.status")).isEqualTo(500);
        assertThat((String) JsonPath.read(resposta.body(), "$.instance")).isEqualTo("/api/teste/erro-inesperado");
        assertThat(resposta.body()).doesNotContain("segredo-interno").doesNotContain("timestamp")
                .doesNotContain("IllegalStateException");
    }
}
