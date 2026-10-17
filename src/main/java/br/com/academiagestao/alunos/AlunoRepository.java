package br.com.academiagestao.alunos;

import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface AlunoRepository extends JpaRepository<Aluno, UUID>, JpaSpecificationExecutor<Aluno> {

    Optional<Aluno> findByCpf(String cpf);

    boolean existsByCpf(String cpf);

    /** SELECT ... FOR UPDATE: a linha fica travada até o fim da transação. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Aluno a WHERE a.id = :id")
    Optional<Aluno> travarPorId(UUID id);

    /**
     * Filtro da listagem. Se a busca só tem dígitos, pontos e traço (e a busca por CPF é
     * permitida), procura pelo começo do CPF; senão, por qualquer parte do nome, sem
     * diferenciar maiúsculas nem acentos.
     */
    static Specification<Aluno> filtro(String busca, boolean incluirInativos, boolean buscaPorCpf) {
        return (root, query, cb) -> {
            List<Predicate> condicoes = new ArrayList<>();
            if (!incluirInativos) {
                condicoes.add(cb.isTrue(root.get("ativo")));
            }
            if (busca != null && !busca.isBlank()) {
                String termo = busca.trim();
                if (buscaPorCpf && termo.matches("[\\d.\\-\\s]+")) {
                    condicoes.add(cb.like(root.get("cpf"), escaparLike(Cpf.normalizar(termo)) + "%", '\\'));
                } else {
                    // Os dois lados passam por unaccent e lower: "jose" acha "José" e "JOSÉ".
                    String padrao = "%" + escaparLike(termo.toLowerCase(Locale.ROOT)) + "%";
                    condicoes.add(cb.like(
                            cb.lower(cb.function("unaccent", String.class, root.get("nome"))),
                            cb.lower(cb.function("unaccent", String.class, cb.literal(padrao))),
                            '\\'));
                }
            }
            return cb.and(condicoes.toArray(Predicate[]::new));
        };
    }

    // Sem isto, buscar "%" ou "_" casaria com todos os alunos (são curingas do LIKE).
    private static String escaparLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
