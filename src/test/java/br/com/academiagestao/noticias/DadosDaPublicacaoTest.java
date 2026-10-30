package br.com.academiagestao.noticias;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import br.com.academiagestao.noticias.DadosDaPublicacao.Link;
import br.com.academiagestao.noticias.DadosDaPublicacao.Tipo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As regras do que se publica (ADR 0047, decisão 6). */
class DadosDaPublicacaoTest {

    private static final Instant INICIO = Instant.parse("2026-11-20T22:00:00Z");

    private static DadosDaPublicacao noticia(List<Link> links) {
        return new DadosDaPublicacao(Tipo.NOTICIA, "Título", "Texto", null, null, null, links);
    }

    @Test
    void limpaOsEspacosDasPontasESemLinksFicaVazio() {
        DadosDaPublicacao d = new DadosDaPublicacao(Tipo.COMUNICADO, "  Aviso  ", "\n Feriado \n", null, null, " ", null);

        assertThat(d.titulo()).isEqualTo("Aviso");
        assertThat(d.texto()).isEqualTo("Feriado");
        assertThat(d.eventoLocal()).isNull();
        assertThat(d.links()).isEmpty();
    }

    @Test
    void tipoTituloETextoSaoObrigatorios() {
        assertThatThrownBy(() -> new DadosDaPublicacao(null, "T", "X", null, null, null, null)).hasMessageContaining("tipo");
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.NOTICIA, " ", "X", null, null, null, null)).hasMessageContaining("titulo");
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.NOTICIA, "T", null, null, null, null, null))
                .isInstanceOf(DadosInvalidosException.class).hasMessage("texto: é obrigatório");
    }

    @Test
    void tamanhosMaximos() {
        assertThat(new DadosDaPublicacao(Tipo.NOTICIA, "a".repeat(150), "b".repeat(5000), null, null, null, null).titulo()).hasSize(150);
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.NOTICIA, "a".repeat(151), "X", null, null, null, null))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("titulo");
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.NOTICIA, "T", "b".repeat(5001), null, null, null, null))
                .hasMessageContaining("texto");
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.EVENTO, "T", "X", INICIO, null, "c".repeat(151), null))
                .hasMessageContaining("eventoLocal");
    }

    @Test
    void oEventoPrecisaDoInicioEOFimNaoVemAntes() {
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.EVENTO, "T", "X", null, null, "Sala 1", null))
                .hasMessageContaining("eventoInicio");
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.EVENTO, "T", "X", INICIO, INICIO.minusSeconds(1), null, null))
                .hasMessageContaining("eventoFim");
        assertThat(new DadosDaPublicacao(Tipo.EVENTO, "T", "X", INICIO, INICIO, "Sala 1", null).eventoFim()).isEqualTo(INICIO);
        assertThat(new DadosDaPublicacao(Tipo.EVENTO, "T", "X", INICIO, null, null, null).eventoFim()).isNull();
    }

    @Test
    void soOEventoTemDataELocal() {
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.NOTICIA, "T", "X", INICIO, null, null, null)).isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.COMUNICADO, "T", "X", null, INICIO, null, null)).isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.NOTICIA, "T", "X", null, null, "Sala", null)).isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void linksComRotuloOpcionalEAte10() {
        DadosDaPublicacao d = noticia(List.of(new Link(" https://academia.exemplo/agenda ", "  "), new Link("http://x.exemplo", "Inscrição")));
        assertThat(d.links()).containsExactly(new Link("https://academia.exemplo/agenda", null), new Link("http://x.exemplo", "Inscrição"));

        assertThat(noticia(Collections.nCopies(10, new Link("https://a.exemplo", null))).links()).hasSize(10);
        assertThatThrownBy(() -> noticia(Collections.nCopies(11, new Link("https://a.exemplo", null)))).hasMessageContaining("no máximo 10");
        assertThatThrownBy(() -> noticia(Collections.singletonList(null))).hasMessageContaining("links");
        assertThatThrownBy(() -> noticia(List.of(new Link("https://a.exemplo/" + "x".repeat(483), null)))).hasMessageContaining("500");
        assertThatThrownBy(() -> noticia(List.of(new Link("https://a.exemplo", "r".repeat(101))))).hasMessageContaining("100");
    }

    @ParameterizedTest(name = "recusa {0}")
    @ValueSource(strings = {"javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html,oi", "ftp://a.exemplo",
            "Https://a.exemplo", "https://", "https:///caminho", "https://a.exemplo/com espaço", "https://a.exemplo/\ttab", "https://a.exemplo/x\u2003y", "//a.exemplo", "a.exemplo",
            "https://a b.exemplo"})
    void soEnderecosDaWeb(String url) {
        assertThatThrownBy(() -> noticia(List.of(new Link(url, null))))
                .isInstanceOf(DadosInvalidosException.class).hasMessageContaining("endereço inválido");
    }

    @ParameterizedTest(name = "aceita {0}")
    @ValueSource(strings = {"https://www.youtube.com/watch?v=abc", "http://192.168.0.10:8080/a?b=c#d", "https://exemplo.com.br"})
    void aceitaEnderecosDaWeb(String url) {
        assertThat(noticia(List.of(new Link(url, null))).links().getFirst().url()).isEqualTo(url);
    }

    /** O título vira o assunto do e-mail do aviso: uma quebra de linha nele abriria outro cabeçalho (diagnóstico). */
    @Test
    void tituloLocalENomeDoLinkSaoDeUmaLinhaSo() {
        for (String quebra : new String[] {"\n", "\r", "\r\n"}) {
            assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.NOTICIA, "Assunto" + quebra + "Bcc: todos@x.com", "X", null, null, null, null))
                    .isInstanceOf(DadosInvalidosException.class).hasMessage("titulo: não pode ter quebra de linha");
            assertThatThrownBy(() -> new DadosDaPublicacao(Tipo.EVENTO, "T", "X", INICIO, null, "Sala" + quebra + "2", null))
                    .hasMessage("eventoLocal: não pode ter quebra de linha");
            assertThatThrownBy(() -> noticia(List.of(new Link("https://a.exemplo", "a" + quebra + "b"))))
                    .hasMessage("links: não pode ter quebra de linha");
        }
        // O texto (o corpo) pode ter várias linhas; nas pontas, o título perde a quebra junto com os espaços.
        assertThat(new DadosDaPublicacao(Tipo.NOTICIA, "T", "linha 1\nlinha 2", null, null, null, null).texto()).isEqualTo("linha 1\nlinha 2");
        assertThat(new DadosDaPublicacao(Tipo.NOTICIA, "\nTítulo\n", "X", null, null, null, null).titulo()).isEqualTo("Título");
    }
}
