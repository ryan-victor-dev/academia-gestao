package br.com.academiagestao.noticias;

import br.com.academiagestao.compartilhado.DadosInvalidosException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;

/**
 * O que se publica (ADR 0047, decisão 6): uma notícia, um comunicado ou um evento, com título, texto e até
 * {@value #MAXIMO_DE_LINKS} links. Só o evento tem quando (início obrigatório, fim opcional) e onde. Texto
 * simples: a tela nunca interpreta HTML. Os links só com http ou https: um "javascript:" num link viraria
 * código no navegador de quem clicasse. Java puro (ADR 0004).
 */
record DadosDaPublicacao(Tipo tipo, String titulo, String texto, Instant eventoInicio, Instant eventoFim,
                         String eventoLocal, List<Link> links) {

    static final int TAMANHO_MAXIMO_TITULO = 150;
    static final int TAMANHO_MAXIMO_TEXTO = 5000;
    static final int TAMANHO_MAXIMO_LOCAL = 150;
    static final int TAMANHO_MAXIMO_URL = 500;
    static final int TAMANHO_MAXIMO_ROTULO = 100;
    static final int MAXIMO_DE_LINKS = 10;

    enum Tipo { NOTICIA, COMUNICADO, EVENTO }

    record Link(String url, String rotulo) {
    }

    DadosDaPublicacao {
        if (tipo == null) {
            throw new DadosInvalidosException("tipo: é obrigatório");
        }
        titulo = obrigatorio("titulo", titulo, TAMANHO_MAXIMO_TITULO);
        texto = obrigatorio("texto", texto == null ? null : texto.strip(), TAMANHO_MAXIMO_TEXTO);
        eventoLocal = opcional("eventoLocal", eventoLocal, TAMANHO_MAXIMO_LOCAL);
        if (tipo == Tipo.EVENTO) {
            if (eventoInicio == null) {
                throw new DadosInvalidosException("eventoInicio: o evento precisa de data e hora");
            }
            if (eventoFim != null && eventoFim.isBefore(eventoInicio)) {
                throw new DadosInvalidosException("eventoFim: não pode ser antes do início");
            }
        } else if (eventoInicio != null || eventoFim != null || eventoLocal != null) {
            throw new DadosInvalidosException("eventoInicio: só o evento tem data, hora e local");
        }
        links = links == null ? List.of() : links.stream().map(DadosDaPublicacao::link).toList();
        if (links.size() > MAXIMO_DE_LINKS) {
            throw new DadosInvalidosException("links: no máximo " + MAXIMO_DE_LINKS);
        }
    }

    private static Link link(Link link) {
        if (link == null) {
            throw new DadosInvalidosException("links: link vazio");
        }
        String url = obrigatorio("links", link.url(), TAMANHO_MAXIMO_URL);
        if (!ehEnderecoDaWeb(url)) {
            throw new DadosInvalidosException("links: endereço inválido (use http:// ou https://): " + url);
        }
        return new Link(url, opcional("links", link.rotulo(), TAMANHO_MAXIMO_ROTULO));
    }

    /**
     * Só "http://" e "https://" (em minúsculas, como o banco confere), com um servidor e sem espaços (a URI
     * recusa qualquer espaço). Quem digitar "Https://" recebe a mensagem, e não um 500 do banco.
     */
    static boolean ehEnderecoDaWeb(String url) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return false;
        }
        try {
            return new URI(url).getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String obrigatorio(String campo, String valor, int maximo) {
        String limpo = opcional(campo, valor, maximo);
        if (limpo == null) {
            throw new DadosInvalidosException(campo + ": é obrigatório");
        }
        return limpo;
    }

    private static String opcional(String campo, String valor, int maximo) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String limpo = valor.strip();
        if (limpo.length() > maximo) {
            throw new DadosInvalidosException(campo + ": pode ter no máximo " + maximo + " caracteres");
        }
        return limpo;
    }
}
