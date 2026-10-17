package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Com o Tomcat de verdade (o MockMvc não passa pela válvula que lê os cabeçalhos do proxy):
 * atrás do Caddy, a aplicação enxerga o IP real de cada cliente e sabe que a conexão foi HTTPS.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "academia.login.tentativas-por-ip=2")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class AtrasDoProxyTest {

    @LocalServerPort
    int porta;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void limpar() {
        jdbc.update("DELETE FROM tentativas_login");
        jdbc.update("DELETE FROM usuarios");
    }

    /** Como o Caddy faria: repassando o IP do cliente e o protocolo original. */
    private HttpResponse<String> loginViaProxy(String ipDoCliente, String protocolo) throws Exception {
        HttpRequest.Builder requisicao = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", ipDoCliente)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\": \"ninguem@x.com\", \"senha\": \"qualquer-123\"}"));
        if (protocolo != null) {
            requisicao.header("X-Forwarded-Proto", protocolo);
        }
        return http.send(requisicao.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void limitePorIpUsaOIpRealDeCadaClienteENaoODoProxy() throws Exception {
        assertThat(loginViaProxy("203.0.113.7", null).statusCode()).isEqualTo(401);
        assertThat(loginViaProxy("203.0.113.7", null).statusCode()).isEqualTo(401);
        assertThat(loginViaProxy("203.0.113.7", null).statusCode()).isEqualTo(429);

        // Outro cliente, mesmo proxy: não herda o bloqueio. Sem ler o X-Forwarded-For,
        // todo mundo pareceria o mesmo IP (o do proxy) e o bloqueio valeria para todos.
        assertThat(loginViaProxy("198.51.100.20", null).statusCode()).isEqualTo(401);
    }

    @Test
    void logRegistraOIpRealDoCliente(CapturedOutput log) throws Exception {
        loginViaProxy("203.0.113.99", null);

        assertThat(log.getAll()).contains("Login recusado: conta ninguem@x.com / IP 203.0.113.99");
    }

    @Test
    void conexaoHttpsNoProxyLigaOHsts() throws Exception {
        assertThat(loginViaProxy("198.51.100.30", "https").headers().firstValue("Strict-Transport-Security"))
                .hasValueSatisfying(valor -> assertThat(valor).contains("max-age="));
        // Sem HTTPS, o HSTS não pode ser enviado (o navegador ignoraria, e seria enganoso).
        assertThat(loginViaProxy("198.51.100.31", null).headers().firstValue("Strict-Transport-Security")).isEmpty();
    }
}
