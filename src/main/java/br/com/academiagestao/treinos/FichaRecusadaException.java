package br.com.academiagestao.treinos;

/** A ficha conflita com o estado atual (aluno ou exercício inativo, ficha encerrada): 409. */
class FichaRecusadaException extends RuntimeException {

    private final String titulo;

    FichaRecusadaException(String titulo, String mensagem) {
        super(mensagem);
        this.titulo = titulo;
    }

    String titulo() {
        return titulo;
    }
}
