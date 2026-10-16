package br.com.academiagestao.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPerfil(Perfil perfil);

    boolean existsByAlunoId(UUID alunoId);

    Optional<Usuario> findByAlunoId(UUID alunoId);

    /** Versão atual dos tokens do usuário, se ele existe e está ativo. */
    @Query("select u.versaoToken from Usuario u where u.id = :id and u.ativo = true")
    Optional<Integer> versaoTokenSeAtivo(UUID id);
}
