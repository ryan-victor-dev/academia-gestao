package br.com.academiagestao.treinos;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

interface FichaRepository extends JpaRepository<Ficha, UUID> {

    @Query("SELECT f FROM Ficha f WHERE f.alunoId = :alunoId AND f.encerradaEm IS NULL")
    Optional<Ficha> buscarAtual(UUID alunoId);

    /** Só o id da atual, sem carregar as divisões e os exercícios (que são EAGER). */
    @Query("SELECT f.id FROM Ficha f WHERE f.alunoId = :alunoId AND f.encerradaEm IS NULL")
    Optional<UUID> idDaAtual(UUID alunoId);

    /**
     * Encerra sem carregar a ficha (pendências baixas, item 23): só o momento e a versão mudam,
     * como no {@link Ficha#encerrar}. Quem chama trava o aluno antes. Devolve 0 se ela já não
     * era a atual.
     */
    @Modifying
    @Query("UPDATE Ficha f SET f.encerradaEm = :momento, f.versao = f.versao + 1 WHERE f.id = :id AND f.encerradaEm IS NULL")
    int encerrar(UUID id, Instant momento);

    /** A atual e as encerradas, da mais nova para a mais antiga, uma página por vez. */
    @Query(value = "SELECT f FROM Ficha f WHERE f.alunoId = :alunoId ORDER BY f.montadaEm DESC, f.id DESC",
            countQuery = "SELECT count(f) FROM Ficha f WHERE f.alunoId = :alunoId")
    Page<Ficha> historico(UUID alunoId, Pageable pagina);

    /**
     * Só o aluno, sem carregar a ficha: quem corrige trava o aluno antes e só então lê a ficha,
     * para ver o que outra transação (uma ficha nova, que a encerra) acabou de gravar.
     */
    @Query("SELECT f.alunoId FROM Ficha f WHERE f.id = :id")
    Optional<UUID> alunoDaFicha(UUID id);

    /**
     * As fichas atuais de alunos ativos com a revisão até {@code hoje}, da revisão mais antiga
     * para a mais nova, uma página por vez. Só os campos da lista, sem carregar divisões e
     * exercícios.
     */
    @Query(value = """
            SELECT new br.com.academiagestao.treinos.FichaRepository$RevisaoVencida(
                f.id, f.alunoId, f.revisaoEm, f.montadaEm, f.montadaPor)
            FROM Ficha f WHERE f.encerradaEm IS NULL AND f.alunoAtivo = true AND f.revisaoEm <= :hoje
            ORDER BY f.revisaoEm, f.montadaEm, f.id""",
            countQuery = """
            SELECT count(f) FROM Ficha f
            WHERE f.encerradaEm IS NULL AND f.alunoAtivo = true AND f.revisaoEm <= :hoje""")
    Page<RevisaoVencida> revisoesVencidas(LocalDate hoje, Pageable pagina);

    /** Ao inativar ou reativar o aluno (ver SituacaoDoAlunoNosTreinos). Só a atual: a encerrada não muda. */
    @Modifying
    @Query(value = "UPDATE fichas SET aluno_ativo = :ativo WHERE aluno_id = :alunoId AND encerrada_em IS NULL",
            nativeQuery = true)
    void marcarAlunoAtivo(UUID alunoId, boolean ativo);

    record RevisaoVencida(UUID fichaId, UUID alunoId, LocalDate revisaoEm, Instant montadaEm, UUID montadaPor) {
    }
}
