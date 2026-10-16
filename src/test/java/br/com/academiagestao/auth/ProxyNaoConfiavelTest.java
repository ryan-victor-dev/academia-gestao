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
 * Quem não é um proxy confiável não pode escolher o próprio IP mandando X-Forwarded-For:
 * senão bastaria trocar o cabeçalho a cada tentativa para furar o limite de login.
 * Aqui o localhost deixa de ser confiável (só 10.9.9.9 seria), e o cabeçalho é ignorado.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.remoteip.internal-proxies=10\\.9\\.9\\.9")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class ProxyNaoConfiavelTest {

    @LocalServerPort
    int porta;

    @Test
    void xForwardedForDeOrigemNaoConfiavelEhIgnorado(CapturedOutput log) throws Exception {
        HttpRequest forjada = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113.66")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\": \"ninguem@x.com\", \"senha\": \"qualquer-123\"}"))
                .build();

        HttpClient.newHttpClient().send(forjada, HttpResponse.BodyHandlers.discarding());

        assertThat(log.getAll())
                .doesNotContain("IP 203.0.113.66")
                .containsPattern("Login recusado: conta ninguem@x.com / IP (127\\.0\\.0\\.1|0:0:0:0:0:0:0:1)");
    }
}
