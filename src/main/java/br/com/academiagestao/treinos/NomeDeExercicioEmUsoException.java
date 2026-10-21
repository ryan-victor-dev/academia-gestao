package br.com.academiagestao.treinos;

class NomeDeExercicioEmUsoException extends RuntimeException {

    NomeDeExercicioEmUsoException() {
        super("Já existe um exercício ativo com este nome");
    }
}
