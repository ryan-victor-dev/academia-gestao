package br.com.academiagestao.compartilhado;

/** Edição sem o cabeçalho If-Match (428). */
public class IfMatchAusenteException extends RuntimeException {

    public IfMatchAusenteException(String recurso) {
        super("Envie o cabeçalho If-Match com o ETag recebido no GET d" + recurso + ".");
    }
}
