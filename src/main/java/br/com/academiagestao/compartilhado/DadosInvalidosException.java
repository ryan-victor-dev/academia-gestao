package br.com.academiagestao.compartilhado;

/**
 * Regra de negócio violada pelos dados que o cliente mandou (ex.: telefone sem DDD).
 * Vira 400 com a mensagem. É uma exceção própria, e não IllegalArgumentException,
 * para que um bug qualquer que lance IllegalArgumentException continue sendo 500
 * (um erro nosso) em vez de aparecer para o cliente como culpa dele.
 */
public class DadosInvalidosException extends IllegalArgumentException {

    public DadosInvalidosException(String mensagem) {
        super(mensagem);
    }
}
