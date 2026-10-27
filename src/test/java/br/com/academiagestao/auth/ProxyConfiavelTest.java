package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O outro lado do {@link ProxyNaoConfiavelTest}: o IP de {@code academia.proxy-confiavel} (aqui o
 * 127.0.0.1, no Docker o do Caddy) continua repassando o IP real do cliente.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "academia.proxy-confiavel=127.0.0.1")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class ProxyConfiavelTest {

    @LocalServerPort
    int porta;

    @Test
    void xForwardedForDoProxyConfiavelEhLido(CapturedOutput log) throws Exception {
        HttpRequest viaProxy = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113.67")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\": \"ninguem@x.com\", \"senha\": \"qualquer-123\"}"))
                .build();

        HttpClient.newHttpClient().send(viaProxy, HttpResponse.BodyHandlers.discarding());

        assertThat(log.getAll()).contains("Login recusado: conta ninguem@x.com / IP 203.0.113.67");
    }
}
