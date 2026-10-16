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

    LoginsDeAlunos(UsuarioRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Cria o login de um aluno com senha temporária. A senha volta em texto puro só
     * nesta resposta: no banco fica apenas o hash, e ninguém consegue recuperá-la depois.
     */
    @Transactional
    public LoginCriado criar(UUID alunoId, String nome, String email) {
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
        return new LoginCriado(usuario.getEmail(), senhaTemporaria);
    }

    public record LoginCriado(String email, String senhaTemporaria) {
    }
}
