package br.com.academiagestao.treinos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * As fichas atuais com a revisão até {@code hoje}, da revisão mais antiga para a mais nova.
     * Só os campos da lista, sem carregar divisões e exercícios.
     */
    @Query("""
            SELECT new br.com.academiagestao.treinos.FichaRepository$RevisaoVencida(
                f.id, f.alunoId, f.revisaoEm, f.montadaEm, f.montadaPor)
            FROM Ficha f WHERE f.encerradaEm IS NULL AND f.revisaoEm <= :hoje
            ORDER BY f.revisaoEm, f.montadaEm""")
    List<RevisaoVencida> revisoesVencidas(LocalDate hoje);

    record RevisaoVencida(UUID fichaId, UUID alunoId, LocalDate revisaoEm, Instant montadaEm, UUID montadaPor) {
    }
}
