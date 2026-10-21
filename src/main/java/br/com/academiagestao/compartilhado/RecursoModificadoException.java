package br.com.academiagestao.compartilhado;

/** O registro foi alterado por outra pessoa depois que o cliente o carregou (412). */
public class RecursoModificadoException extends RuntimeException {

    private final String titulo;

    /** @param recurso nome do registro com artigo, ex.: "o aluno", "a ficha" (o artigo dá o gênero) */
    public RecursoModificadoException(String recurso) {
        super(maiuscula(recurso) + " foi " + alterado(recurso) + " por outra pessoa depois que você "
                + recurso.substring(0, recurso.indexOf(' ')) + " carregou. Carregue de novo (GET) e refaça a edição.");
        this.titulo = maiuscula(recurso.substring(recurso.indexOf(' ') + 1)) + " " + alterado(recurso)
                + " por outra pessoa";
    }

    private static String alterado(String recurso) {
        return recurso.startsWith("a ") ? "alterada" : "alterado";
    }

    String titulo() {
        return titulo;
    }

    private static String maiuscula(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }
}
