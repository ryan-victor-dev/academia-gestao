package br.com.academiagestao.treinos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FichaRepository extends JpaRepository<Ficha, UUID> {

    @Query("SELECT f FROM Ficha f WHERE f.alunoId = :alunoId AND f.encerradaEm IS NULL")
    Optional<Ficha> buscarAtual(UUID alunoId);

    /** A atual e as encerradas, da mais nova para a mais antiga. */
    List<Ficha> findByAlunoIdOrderByMontadaEmDesc(UUID alunoId);

    /**
     * Só o aluno, sem carregar a ficha: quem corrige trava o aluno antes e só então lê a ficha,
     * para ver o que outra transação (uma ficha nova, que a encerra) acabou de gravar.
     */
    @Query("SELECT f.alunoId FROM Ficha f WHERE f.id = :id")
    Optional<UUID> alunoDaFicha(UUID id);
}
