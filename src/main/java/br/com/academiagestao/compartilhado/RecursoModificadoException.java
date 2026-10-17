package br.com.academiagestao.compartilhado;

/** O registro foi alterado por outra pessoa depois que o cliente o carregou (412). */
public class RecursoModificadoException extends RuntimeException {

    private final String titulo;

    /** @param recurso nome do registro com artigo, ex.: "o aluno", "o plano" */
    public RecursoModificadoException(String recurso) {
        super(maiuscula(recurso) + " foi alterado por outra pessoa depois que você o carregou. "
                + "Carregue de novo (GET) e refaça a edição.");
        this.titulo = maiuscula(recurso.substring(recurso.indexOf(' ') + 1)) + " alterado por outra pessoa";
    }

    String titulo() {
        return titulo;
    }

    private static String maiuscula(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }
}
