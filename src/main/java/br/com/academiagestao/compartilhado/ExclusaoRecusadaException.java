package br.com.academiagestao.compartilhado;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/** O cadastro já tem histórico e não pode ser excluído; o caminho é inativar (409, ADR 0043). */
public class ExclusaoRecusadaException extends RuntimeException {

    // Código do PostgreSQL para "outra tabela ainda aponta para esta linha".
    private static final String CHAVE_ESTRANGEIRA = "23503";

    private final String titulo;

    public ExclusaoRecusadaException(String titulo, String detalhe) {
        super(detalhe);
        this.titulo = titulo;
    }

    String titulo() {
        return titulo;
    }

    /**
     * A recusa do banco foi uma chave estrangeira (alguma tabela aponta para o cadastro)? Só ela
     * é "tem histórico"; outra recusa é um erro de verdade (500), e não uma mensagem errada.
     */
    public static boolean temHistorico(DataIntegrityViolationException e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql && CHAVE_ESTRANGEIRA.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
