package br.com.academiagestao.planos;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanoRepository extends JpaRepository<Plano, UUID> {

    List<Plano> findAllByOrderByNomeAsc();

    List<Plano> findByAtivoTrueOrderByNomeAsc();

    /** SELECT ... FOR UPDATE: a linha fica travada até o fim da transação (excluir). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Plano p WHERE p.id = :id")
    Optional<Plano> travarPorId(UUID id);

    /**
     * SELECT ... FOR SHARE: várias matrículas no mesmo plano não se esperam, mas a exclusão
     * espera por elas (e elas, pela exclusão). Ver PlanosParaMatricula.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM Plano p WHERE p.id = :id")
    Optional<Plano> travarParaMatricula(UUID id);

    /**
     * Há outro plano ativo com o mesmo nome (sem acento e sem diferenciar maiúsculas)?
     * Usa a mesma função do índice único do banco, para as duas regras nunca divergirem.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM planos
                           WHERE ativo AND id <> :ignorar AND nome_normalizado(nome) = nome_normalizado(:nome))""",
            nativeQuery = true)
    boolean existeOutroAtivoComNome(String nome, UUID ignorar);
}
