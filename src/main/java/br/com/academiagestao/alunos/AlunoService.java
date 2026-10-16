package br.com.academiagestao.alunos;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    @Transactional(readOnly = true)
    Page<Aluno> listar(String busca, boolean incluirInativos, int pagina, int tamanho) {
        // Ordem fixa por nome (e id, para empates não trocarem de página entre consultas).
        PageRequest pageRequest = PageRequest.of(pagina, tamanho, Sort.by("nome", "id"));
        return repository.findAll(AlunoRepository.filtro(busca, incluirInativos), pageRequest);
    }

    @Transactional
    Aluno inativar(UUID id) {
        Aluno aluno = buscar(id);
        aluno.inativar();
        return aluno;
    }

    @Transactional
    Aluno reativar(UUID id) {
        Aluno aluno = buscar(id);
        aluno.reativar();
        return aluno;
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
