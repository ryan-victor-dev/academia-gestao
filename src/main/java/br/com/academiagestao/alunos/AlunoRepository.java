package br.com.academiagestao.alunos;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AlunoRepository extends JpaRepository<Aluno, UUID> {

    Optional<Aluno> findByCpf(String cpf);

    boolean existsByCpf(String cpf);
}
