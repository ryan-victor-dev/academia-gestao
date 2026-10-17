package br.com.academiagestao.matriculas;

/** A matrícula conflita com o estado atual (aluno ou plano inativo, período ocupado): 409. */
class MatriculaRecusadaException extends RuntimeException {

    private final String titulo;

    MatriculaRecusadaException(String titulo, String mensagem) {
        super(mensagem);
        this.titulo = titulo;
    }

    String titulo() {
        return titulo;
    }
}
