package br.com.academiagestao.compartilhado;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * O correio (ADR 0038): desligado sem servidor; com servidor, envia de verdade por SMTP. O envio
 * vai para um Mailpit (a mesma caixa de testes do Docker), e o teste lê a mensagem pela API dele.
 */
class CorreioTest {

    private final CorreioConfig config = new CorreioConfig();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static EmailProperties propriedades(String host, int porta, String remetente, Duration espera) {
        return new EmailProperties(host, porta, null, null, false, remetente, espera);
    }

    @Test
    void semServidorFicaDesligado() {
        Correio correio = config.correio(propriedades(null, 587, null, Duration.ofSeconds(10)));
        assertThat(correio.ligado()).isFalse();
        assertThatThrownBy(() -> correio.enviar(new Correio.Mensagem("a@a.com", "x", "y")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(config.correio(propriedades("  ", 587, null, Duration.ofSeconds(10))).ligado())
                .as("só espaços").isFalse();
    }

    @Test
    void servidorSemRemetenteNaoSobe() {
        assertThatThrownBy(() -> config.correio(propriedades("smtp.exemplo.com", 587, " ", Duration.ofSeconds(10))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("remetente");
    }

    @Test
    void enviaDeVerdadePorSmtp() throws Exception {
        try (var mailpit = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.3"))
                .withExposedPorts(1025, 8025).waitingFor(Wait.forHttp("/livez").forPort(8025))) {
            mailpit.start();
            Correio correio = config.correio(propriedades(mailpit.getHost(), mailpit.getMappedPort(1025),
                    "Academia <nao-responda@academia.local>", Duration.ofSeconds(10)));
            assertThat(correio.ligado()).isTrue();

            correio.enviar(new Correio.Mensagem("aluna@exemplo.com", "Seu código: 123456",
                    "Olá! Seu código de confirmação é 123456. Ele vale 15 minutos."));

            String api = "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + "/api/v1/";
            HttpClient http = HttpClient.newHttpClient();
            JsonNode lista = JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(api + "messages")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertThat(lista.get("total").asInt()).isEqualTo(1);
            JsonNode resumo = lista.get("messages").get(0);
            assertThat(resumo.get("Subject").asText()).isEqualTo("Seu código: 123456");
            assertThat(resumo.get("From").get("Address").asText()).isEqualTo("nao-responda@academia.local");
            assertThat(resumo.get("To").get(0).get("Address").asText()).isEqualTo("aluna@exemplo.com");
            JsonNode mensagem = JSON.readTree(http.send(HttpRequest.newBuilder(
                    URI.create(api + "message/" + resumo.get("ID").asText())).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertThat(mensagem.get("Text").asText()).contains("Olá! Seu código de confirmação é 123456");
            assertThat(mensagem.get("HTML").asText()).as("só texto").isEmpty();
        }
    }

    @Test
    void servidorForaDoArViraIndisponivel() throws Exception {
        int portaFechada;
        try (ServerSocket socket = new ServerSocket(0)) {
            portaFechada = socket.getLocalPort();
        }
        Correio correio = config.correio(propriedades("127.0.0.1", portaFechada, "a@academia.local", Duration.ofSeconds(2)));

        assertThatThrownBy(() -> correio.enviar(new Correio.Mensagem("fulano@exemplo.com", "x", "y")))
                .isInstanceOf(CorreioIndisponivelException.class)
                .hasMessageNotContaining("fulano@exemplo.com");
    }

    /** Servidor que aceita a conexão e nunca responde: o teto de espera (2 s aqui) solta o envio. */
    @Test
    void servidorMudoNaoPrendeOEnvio() throws Exception {
        try (ServerSocket mudo = new ServerSocket(0)) {
            Thread aceita = Thread.ofVirtual().start(() -> {
                try {
                    mudo.accept();
                    Thread.sleep(Duration.ofMinutes(1));
                } catch (Exception e) {
                    // fim do teste
                }
            });
            Correio correio = config.correio(propriedades("127.0.0.1", mudo.getLocalPort(), "a@academia.local",
                    Duration.ofSeconds(2)));

            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThatThrownBy(() ->
                    correio.enviar(new Correio.Mensagem("fulano@exemplo.com", "x", "y")))
                    .isInstanceOf(CorreioIndisponivelException.class));
            aceita.interrupt();
        }
    }
}
