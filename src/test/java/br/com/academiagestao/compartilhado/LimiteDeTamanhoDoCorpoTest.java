package br.com.academiagestao.compartilhado;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Corpo acima de 64 KB é recusado com 413, declarado ou enviado em partes. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LimiteDeTamanhoDoCorpoTest {

    @LocalServerPort
    int porta;

    private final HttpClient http = HttpClient.newHttpClient();

    private static byte[] loginComSenhaDe(int tamanho) {
        return ("{\"email\": \"a@b.com\", \"senha\": \"" + "x".repeat(tamanho) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private HttpResponse<String> enviar(HttpRequest.BodyPublisher corpo) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(corpo).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void corpoGrandeComTamanhoDeclaradoEhRecusadoAntesDeSerLido() throws Exception {
        HttpResponse<String> resposta = enviar(HttpRequest.BodyPublishers.ofByteArray(loginComSenhaDe(100_000)));

        assertThat(resposta.statusCode()).isEqualTo(413);
        assertThat(resposta.headers().firstValue("Content-Type")).hasValue("application/problem+json;charset=UTF-8");
        assertThat(resposta.body()).contains("\"title\":\"Corpo grande demais\"", "no máximo 64 KB", "\"status\":413");
    }

    @Test
    void corpoGrandeEnviadoEmPartesSemTamanhoDeclaradoTambemEhRecusado() throws Exception {
        // ofInputStream não informa o tamanho: o corpo vai em partes (chunked).
        byte[] corpo = loginComSenhaDe(100_000);
        HttpResponse<String> resposta = enviar(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(corpo)));

        assertThat(resposta.request().headers().firstValue("Content-Length")).isEmpty();
        assertThat(resposta.statusCode()).isEqualTo(413);
        assertThat(resposta.body()).contains("Corpo grande demais");
    }

    @Test
    void corpoNormalPassa() throws Exception {
        assertThat(enviar(HttpRequest.BodyPublishers.ofByteArray(loginComSenhaDe(10_000))).statusCode()).isEqualTo(401);
        byte[] corpo = loginComSenhaDe(10_000);
        assertThat(enviar(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(corpo))).statusCode())
                .isEqualTo(401);
    }
}
