package br.com.academiagestao.treinos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface ExercicioRepository extends JpaRepository<Exercicio, UUID> {

    /** Em ordem de nome; {@code grupo} nulo = todos, {@code incluirInativos} falso = só os ativos. */
    @Query("""
            SELECT e FROM Exercicio e
            WHERE (:grupo IS NULL OR e.grupoMuscular = :grupo) AND (:incluirInativos = true OR e.ativo = true)
            ORDER BY e.nome""")
    List<Exercicio> listar(GrupoMuscular grupo, boolean incluirInativos);

    /**
     * Há outro exercício ativo com o mesmo nome (sem acento e sem diferenciar maiúsculas)?
     * Usa a mesma função do índice único do banco, para as duas regras nunca divergirem.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM exercicios
                           WHERE ativo AND id <> :ignorar AND nome_normalizado(nome) = nome_normalizado(:nome))""",
            nativeQuery = true)
    boolean existeOutroAtivoComNome(String nome, UUID ignorar);
}
