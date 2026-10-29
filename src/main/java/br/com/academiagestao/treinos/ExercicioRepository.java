package br.com.academiagestao.treinos;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ExercicioRepository extends JpaRepository<Exercicio, UUID> {

    /** Em ordem de nome; {@code grupo} nulo = todos, {@code incluirInativos} falso = só os ativos. */
    @Query("""
            SELECT e FROM Exercicio e
            WHERE (:grupo IS NULL OR e.grupoMuscular = :grupo) AND (:incluirInativos = true OR e.ativo = true)
            ORDER BY e.nome""")
    List<Exercicio> listar(GrupoMuscular grupo, boolean incluirInativos);

    /** SELECT ... FOR UPDATE: a linha fica travada até o fim da transação (excluir). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Exercicio e WHERE e.id = :id")
    Optional<Exercicio> travarPorId(UUID id);

    /**
     * SELECT ... FOR SHARE: várias fichas com o mesmo exercício não se esperam, mas a exclusão
     * espera por elas (e elas, pela exclusão). Ver FichaService.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT e FROM Exercicio e WHERE e.id IN :ids")
    List<Exercicio> travarParaFicha(Collection<UUID> ids);

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
