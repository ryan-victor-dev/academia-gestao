package br.com.academiagestao.alunos;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class AlunoService {

    private final AlunoRepository repository;

    AlunoService(AlunoRepository repository) {
        this.repository = repository;
    }

    @Transactional
    Aluno cadastrar(DadosAluno dados) {
        Aluno aluno = Aluno.novo(dados);
        if (repository.existsByCpf(aluno.getCpf())) {
            throw new CpfJaCadastradoException();
        }
        return gravar(aluno);
    }

    @Transactional(readOnly = true)
    Aluno buscar(UUID id) {
        return repository.findById(id).orElseThrow(() -> new AlunoNaoEncontradoException(id));
    }

    @Transactional
    Aluno atualizar(UUID id, DadosAluno dados) {
        Aluno aluno = buscar(id);
        String cpfNovo = Cpf.normalizar(dados.cpf());
        if (!cpfNovo.equals(aluno.getCpf()) && repository.existsByCpf(cpfNovo)) {
            throw new CpfJaCadastradoException();
        }
        aluno.atualizar(dados);
        return gravar(aluno);
    }

    // A checagem existsByCpf dá uma mensagem clara no caso comum. Mas duas pessoas
    // cadastrando o mesmo CPF ao mesmo tempo passariam as duas pela checagem; aí quem
    // barra é o UNIQUE do banco, e o erro dele vira o mesmo 409.
    private Aluno gravar(Aluno aluno) {
        try {
            return repository.saveAndFlush(aluno);
        } catch (DataIntegrityViolationException e) {
            throw new CpfJaCadastradoException();
        }
    }
}
