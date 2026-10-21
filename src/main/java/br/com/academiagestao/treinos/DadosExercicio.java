package br.com.academiagestao.treinos;

/** Dados editáveis de um exercício do catálogo, usados no cadastro e na edição. */
record DadosExercicio(String nome, GrupoMuscular grupoMuscular, String observacao) {
}
