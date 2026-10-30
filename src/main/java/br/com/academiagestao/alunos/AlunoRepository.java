package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.Cpf;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.ArrayList;
import java.util.Collection;
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

    /** Só o contato dos ativos, sem carregar o cadastro inteiro (os avisos da academia, ADR 0047). */
    @Query("""
            SELECT new br.com.academiagestao.alunos.AlunoRepository$ContatoDoAluno(a.id, a.nome, a.email, a.telefone)
            FROM Aluno a WHERE a.ativo = true""")
    List<ContatoDoAluno> contatosDosAtivos();

    record ContatoDoAluno(UUID id, String nome, String email, String telefone) {
    }

    @Query("""
            SELECT new br.com.academiagestao.alunos.AlunoRepository$NomeDoAluno(a.id, a.nome)
            FROM Aluno a WHERE a.id IN :ids AND a.ativo = true""")
    List<NomeDoAluno> nomesDosAtivos(Collection<UUID> ids);

    @Query("""
            SELECT new br.com.academiagestao.alunos.AlunoRepository$NomeDoAluno(a.id, a.nome)
            FROM Aluno a WHERE a.id IN :ids""")
    List<NomeDoAluno> nomes(Collection<UUID> ids);

    record NomeDoAluno(UUID id, String nome) {
    }

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
                    // Os dois lados sem acento e minúsculos: "jose" acha "José" e "JOSÉ". O do aluno
                    // já vem gravado (nome_busca, V52), com o índice de trigramas; o da busca, a
                    // mesma função que o gera.
                    String padrao = "%" + escaparLike(termo.toLowerCase(Locale.ROOT)) + "%";
                    condicoes.add(cb.like(
                            root.get("nomeBusca"),
                            cb.function("nome_normalizado", String.class, cb.literal(padrao)),
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
