package br.com.academiagestao.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByCpf(String cpf);

    boolean existsByPerfil(Perfil perfil);

    boolean existsByAlunoId(UUID alunoId);

    Optional<Usuario> findByAlunoId(UUID alunoId);

    /** A equipe: todos os logins menos os de aluno. */
    List<Usuario> findByPerfilNot(Perfil perfil, Sort ordem);

    /** Logins de aluno cujo nome (em minúsculas) ou e-mail casa com o padrão do LIKE. */
    @Query("select u from Usuario u where u.perfil = br.com.academiagestao.auth.Perfil.ALUNO "
            + "and (lower(u.nome) like :padrao escape '\\' or u.email like :padrao escape '\\')")
    Page<Usuario> buscarLoginsDeAlunos(String padrao, Pageable pagina);

    /** SELECT ... FOR UPDATE: a linha fica travada até o fim da transação (excluir). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.id = :id")
    Optional<Usuario> travarPorId(UUID id);

    /** ADMINs ativos, com as linhas travadas até o fim da transação (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.perfil = br.com.academiagestao.auth.Perfil.ADMIN "
            + "and u.ativo = true order by u.id")
    List<Usuario> travarAdminsAtivos();

    /**
     * O login já foi usado (ADR 0043)? Entrou ou abriu um cadastro (registro de acessos), ou é autor
     * na trilha. Uma senha errada não conta: qualquer um que tentasse adivinhar impediria a exclusão.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM acessos WHERE usuario_id = :id AND tipo IN ('LOGIN', 'CONSULTA_ALUNO'))
                OR EXISTS (SELECT 1 FROM auditoria WHERE autor = CAST(:id AS text))""", nativeQuery = true)
    boolean foiUsado(UUID id);

    /** Versão atual dos tokens do usuário, se ele existe e está ativo. */
    @Query("select u.versaoToken from Usuario u where u.id = :id and u.ativo = true")
    Optional<Integer> versaoTokenSeAtivo(UUID id);
}
