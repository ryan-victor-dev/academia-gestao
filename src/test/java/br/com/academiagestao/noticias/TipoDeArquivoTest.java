package br.com.academiagestao.noticias;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O tipo do anexo pelo conteúdo, o nome do arquivo e o endereço assinado (ADR 0047). */
class TipoDeArquivoTest {

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex.replace(" ", ""));
    }

    private static byte[] texto(String texto) {
        return texto.getBytes(StandardCharsets.ISO_8859_1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "JPEG, ffd8ffe000104a464946000101, image/jpeg, IMAGEM",
            "PNG,  89504e470d0a1a0a0000000d49, image/png,  IMAGEM",
            "MP4,  000000206674797069736f6d00, video/mp4,  VIDEO",
            "WEBM, 1a45dfa3a3428681010000000a, video/webm, VIDEO"})
    void reconhecePelosPrimeirosBytes(TipoDeArquivo esperado, String inicio, String conteudoTipo, TipoDeArquivo.Grupo grupo) {
        assertThat(TipoDeArquivo.reconhecer(hex(inicio))).contains(esperado);
        assertThat(esperado.conteudoTipo()).isEqualTo(conteudoTipo);
        assertThat(esperado.grupo()).isEqualTo(grupo);
        assertThat(TipoDeArquivo.doConteudoTipo(conteudoTipo)).isEqualTo(esperado);
    }

    @Test
    void reconheceOsDeTexto() {
        assertThat(TipoDeArquivo.reconhecer(texto("RIFF\u0010\u0000\u0000\u0000WEBPVP8 "))).contains(TipoDeArquivo.WEBP);
        assertThat(TipoDeArquivo.reconhecer(texto("GIF87a\u0001\u0000"))).contains(TipoDeArquivo.GIF);
        assertThat(TipoDeArquivo.reconhecer(texto("GIF89a\u0001\u0000"))).contains(TipoDeArquivo.GIF);
        assertThat(TipoDeArquivo.reconhecer(texto("%PDF-1.7\n%"))).contains(TipoDeArquivo.PDF);
    }

    @ParameterizedTest(name = "recusa {0}")
    @ValueSource(strings = {"<html><script>alert(1)</script>", "<svg xmlns=\"http://www.w3.org/2000/svg\">",
            "RIFF\u0010\u0000\u0000\u0000WAVEfmt ", "GIF88a", "%PD", "PK\u0003\u0004 zip", ""})
    void recusaOQueNaoEhAceito(String inicio) {
        assertThat(TipoDeArquivo.reconhecer(texto(inicio))).isEmpty();
    }

    @Test
    void curtoDemaisNaoQuebra() {
        assertThat(TipoDeArquivo.reconhecer(hex("ffd8"))).isEmpty();
        assertThat(TipoDeArquivo.reconhecer(texto("RIFF1234WEB"))).isEmpty();
        assertThat(TipoDeArquivo.reconhecer(texto("1234fty"))).isEmpty();
        // Do tamanho exato da assinatura, ainda reconhece.
        assertThat(TipoDeArquivo.reconhecer(texto("%PDF-"))).contains(TipoDeArquivo.PDF);
        assertThat(TipoDeArquivo.reconhecer(hex("ffd8ff"))).contains(TipoDeArquivo.JPEG);
    }

    @Test
    void webpPrecisaDasDuasMarcas() {
        assertThat(TipoDeArquivo.reconhecer(texto("ABCD\u0010\u0000\u0000\u0000WEBPVP8 "))).isEmpty();
        assertThat(TipoDeArquivo.reconhecer(texto("RIFF\u0010\u0000\u0000\u0000AVI LIST"))).isEmpty();
    }

    @Test
    void tetosPorGrupo() {
        assertThat(TipoDeArquivo.Grupo.IMAGEM.tamanhoMaximo()).isEqualTo(5L * 1024 * 1024);
        assertThat(TipoDeArquivo.Grupo.DOCUMENTO.tamanhoMaximo()).isEqualTo(10L * 1024 * 1024);
        assertThat(TipoDeArquivo.Grupo.VIDEO.tamanhoMaximo()).isEqualTo(50L * 1024 * 1024);
    }

    @Test
    void nomeDoArquivoSemCaminhoNemControle() {
        assertThat(AnexoService.nomeDoArquivo(" foto da turma.jpg ")).isEqualTo("foto da turma.jpg");
        assertThat(AnexoService.nomeDoArquivo("C:\\Users\\ana\\regulamento.pdf")).isEqualTo("regulamento.pdf");
        assertThat(AnexoService.nomeDoArquivo("../../etc/passwd")).isEqualTo("passwd");
        assertThat(AnexoService.nomeDoArquivo("a".repeat(200))).hasSize(200);
        for (String ruim : new String[] {null, " ", "pasta/", "nome\r\ncom quebra", "a".repeat(201)}) {
            assertThatThrownBy(() -> AnexoService.nomeDoArquivo(ruim)).as(String.valueOf(ruim)).isInstanceOf(DadosInvalidosException.class);
        }
    }

    @Test
    void enderecoAssinadoValeAteAViradaDaHoraEDepoisNao() {
        String segredo = Base64.getEncoder().encodeToString(new byte[32]);
        Instant agora = Instant.parse("2026-11-10T14:20:00Z");
        AssinaturaDosAnexos assinatura = new AssinaturaDosAnexos(segredo, Clock.fixed(agora, ZoneOffset.UTC));
        UUID anexo = UUID.randomUUID();

        AssinaturaDosAnexos.Endereco endereco = assinatura.assinar(anexo);
        assertThat(endereco.expira()).isEqualTo(Instant.parse("2026-11-10T16:00:00Z"));
        assertThat(assinatura.assinar(anexo)).isEqualTo(endereco);
        String valor = endereco.caminho().replaceAll(".*assinatura=", "");
        long expira = endereco.expira().getEpochSecond();
        assertThat(endereco.caminho()).isEqualTo("/api/anexos/" + anexo + "?expira=" + expira + "&assinatura=" + valor);

        assertThat(assinatura.confere(anexo, expira, valor)).isTrue();
        assertThat(assinatura.restante(expira)).hasMinutes(100);
        assertThat(assinatura.confere(UUID.randomUUID(), expira, valor)).as("outro anexo").isFalse();
        assertThat(assinatura.confere(anexo, expira + 3600, valor)).as("prazo mudado").isFalse();
        assertThat(assinatura.confere(anexo, expira, valor.substring(1) + "A")).as("assinatura mudada").isFalse();
        assertThat(assinatura.confere(anexo, expira, null)).isFalse();

        AssinaturaDosAnexos depois = new AssinaturaDosAnexos(segredo, Clock.fixed(endereco.expira().plusSeconds(1), ZoneOffset.UTC));
        assertThat(depois.confere(anexo, expira, valor)).as("vencido").isFalse();
        assertThat(depois.restante(expira)).isZero();
        AssinaturaDosAnexos outraChave = new AssinaturaDosAnexos(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}), Clock.fixed(agora, ZoneOffset.UTC));
        assertThat(outraChave.confere(anexo, expira, valor)).as("outra chave").isFalse();
    }
}
