package br.com.academiagestao.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * O que o módulo de alunos pode pedir ao módulo de autenticação. É a única porta de
 * entrada pública: o resto do auth (Usuario, repositório) continua interno.
 */
@Service
public class LoginsDeAlunos {

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final RedefinicaoDeSenha redefinicaoDeSenha;

    LoginsDeAlunos(UsuarioRepository repository, PasswordEncoder passwordEncoder,
                   RedefinicaoDeSenha redefinicaoDeSenha) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.redefinicaoDeSenha = redefinicaoDeSenha;
    }

    /**
     * Cria o login de um aluno com senha temporária. A senha volta em texto puro só
     * nesta resposta: no banco fica apenas o hash, e ninguém consegue recuperá-la depois.
     */
    @Transactional
    public CredenciaisTemporarias criar(UUID alunoId, String nome, String email) {
        if (repository.existsByAlunoId(alunoId)) {
            throw new LoginJaExisteException("Este aluno já tem login");
        }
        String emailNormalizado = Usuario.normalizarEmail(email);
        if (repository.existsByEmail(emailNormalizado)) {
            throw new LoginJaExisteException("Este e-mail já é usado por outro login");
        }

        String senhaTemporaria = GeradorDeSenhaTemporaria.gerar();
        Usuario usuario = Usuario.deAluno(nome, emailNormalizado, passwordEncoder.encode(senhaTemporaria), alunoId);
        usuario.exigirTrocaDeSenha();
        try {
            repository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            // Dois pedidos ao mesmo tempo passam pelas checagens acima; o UNIQUE do banco barra o segundo.
            throw new LoginJaExisteException("Este aluno já tem login ou o e-mail já está em uso");
        }
        return new CredenciaisTemporarias(usuario.getEmail(), senhaTemporaria);
    }

    /** O aluno esqueceu a senha: gera uma nova senha temporária para o login dele. */
    @Transactional
    public CredenciaisTemporarias redefinirSenha(UUID alunoId) {
        Usuario usuario = repository.findByAlunoId(alunoId)
                .orElseThrow(() -> new UsuarioNaoEncontradoException("Este aluno ainda não tem login"));
        return redefinicaoDeSenha.aplicar(usuario);
    }

    /** Aluno inativado: o login dele (se existir) para de funcionar. */
    @Transactional
    public void desativar(UUID alunoId) {
        repository.findByAlunoId(alunoId).ifPresent(Usuario::desativar);
    }

    /** Aluno reativado: o login dele (se existir) volta a funcionar. */
    @Transactional
    public void reativar(UUID alunoId) {
        repository.findByAlunoId(alunoId).ifPresent(Usuario::reativar);
    }
}
