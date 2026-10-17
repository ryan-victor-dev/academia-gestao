package br.com.academiagestao.matriculas;

import jakarta.persistence.LockModeType;
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

    /** Matrículas não canceladas do aluno cujo período cruza com [inicio, fim]: a regra do banco. */
    @Query("""
            SELECT m FROM Matricula m
            WHERE m.alunoId = :alunoId AND m.canceladaEm IS NULL AND m.inicio <= :fim AND m.fim >= :inicio
            ORDER BY m.inicio""")
    List<Matricula> sobrepostas(UUID alunoId, LocalDate inicio, LocalDate fim);

    /** SELECT ... FOR UPDATE: dois cancelamentos da mesma matrícula não passam juntos. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Matricula m WHERE m.id = :id")
    Optional<Matricula> travarPorId(UUID id);
}
