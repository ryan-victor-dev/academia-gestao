package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.CadastroParaPrimeiroAcesso;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/** O cadastro responde ao primeiro acesso do aluno (ADR 0039): só o aluno ativo, com o mesmo e-mail. */
@Component
class CadastroDoAlunoParaPrimeiroAcesso implements CadastroParaPrimeiroAcesso {

    private final AlunoRepository repository;

    CadastroDoAlunoParaPrimeiroAcesso(AlunoRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AlunoDoCadastro> ativoCom(String cpf, String email) {
        if (cpf == null || email == null) {
            return Optional.empty();
        }
        String digitado = email.trim().toLowerCase(Locale.ROOT);
        return repository.findByCpf(Cpf.normalizar(cpf))
                .filter(Aluno::isAtivo)
                .filter(aluno -> aluno.getEmail() != null && aluno.getEmail().toLowerCase(Locale.ROOT).equals(digitado))
                .map(aluno -> new AlunoDoCadastro(aluno.getId(), aluno.getNome(), digitado));
    }
}
