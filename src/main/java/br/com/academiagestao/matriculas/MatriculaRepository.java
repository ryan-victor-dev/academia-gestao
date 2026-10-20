package br.com.academiagestao.matriculas;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MatriculaRepository extends JpaRepository<Matricula, UUID> {

    /** Histórico do aluno, da mais recente para a mais antiga (inclusive as canceladas). */
    List<Matricula> findByAlunoIdOrderByInicioDesc(UUID alunoId);

    /**
     * Matrículas não canceladas do aluno cujo período cruza com [inicio, fim]: a regra do banco.
     * A trocada fica de fora: ela vale só até a véspera da troca, e as consultas são de hoje
     * em diante.
     */
    @Query("""
            SELECT m FROM Matricula m
            WHERE m.alunoId = :alunoId AND m.canceladaEm IS NULL AND m.trocadaEm IS NULL
              AND m.inicio <= :fim AND m.fim >= :inicio
            ORDER BY m.inicio""")
    List<Matricula> sobrepostas(UUID alunoId, LocalDate inicio, LocalDate fim);

    /** Não canceladas nem trocadas que ainda não terminaram: vigente, trancada ou agendada. */
    @Query("""
            SELECT m FROM Matricula m
            WHERE m.alunoId = :alunoId AND m.canceladaEm IS NULL AND m.trocadaEm IS NULL AND m.fim >= :hoje
            ORDER BY m.inicio""")
    List<Matricula> pendentes(UUID alunoId, LocalDate hoje);

    @Query("SELECT m.alunoId FROM Matricula m WHERE m.id = :id")
    Optional<UUID> alunoDaMatricula(UUID id);

    /** A primeira matrícula não cancelada do aluno que começa depois do dia informado (renovação). */
    Optional<Matricula> findFirstByAlunoIdAndCanceladaEmIsNullAndInicioAfterOrderByInicio(UUID alunoId, LocalDate dia);

    /** Ids em ordem, a partir de um (paginação por chave, para percorrer todas). */
    @Query("SELECT m.id FROM Matricula m WHERE m.id > :depoisDe ORDER BY m.id")
    List<UUID> idsDepoisDe(UUID depoisDe, Pageable pagina);

    /** SELECT ... FOR UPDATE: dois cancelamentos da mesma matrícula não passam juntos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Matricula m WHERE m.id = :id")
    Optional<Matricula> travarPorId(UUID id);
}
