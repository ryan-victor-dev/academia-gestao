package br.com.academiagestao.planos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PlanoRepository extends JpaRepository<Plano, UUID> {

    List<Plano> findAllByOrderByNomeAsc();

    List<Plano> findByAtivoTrueOrderByNomeAsc();

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
