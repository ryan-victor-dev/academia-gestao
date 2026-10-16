package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Cadastro da equipe (ADMIN, RECEPCAO, INSTRUTOR). Logins de aluno ficam no módulo de alunos. */
@Service
class EquipeService {

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;

    EquipeService(UsuarioRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    UsuarioCriado criar(String nome, String email, Perfil perfil) {
        if (perfil == Perfil.ALUNO) {
            throw new DadosInvalidosException("login de aluno é criado pelo cadastro do aluno: POST /api/alunos/{id}/login");
        }
        String emailNormalizado = Usuario.normalizarEmail(email);
        if (repository.existsByEmail(emailNormalizado)) {
            throw new LoginJaExisteException("Este e-mail já é usado por outro login");
        }

        String senhaTemporaria = GeradorDeSenhaTemporaria.gerar();
        Usuario usuario = Usuario.daEquipe(nome, emailNormalizado, passwordEncoder.encode(senhaTemporaria), perfil);
        usuario.exigirTrocaDeSenha();
        try {
            repository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            throw new LoginJaExisteException("Este e-mail já é usado por outro login");
        }
        return new UsuarioCriado(usuario, senhaTemporaria);
    }

    /**
     * Funcionário que saiu. Os tokens dele caem na hora (ADR 0010).
     *
     * Duas regras para a academia nunca ficar sem ADMIN: ninguém desativa a si mesmo, e
     * o último ADMIN ativo não pode ser desativado. A segunda só é alcançada numa corrida
     * (A desativa B enquanto B desativa A): por isso as linhas dos ADMINs ativos são
     * travadas (SELECT ... FOR UPDATE) antes de contar, e a segunda transação espera a
     * primeira terminar para contar de novo.
     */
    @Transactional
    UsuarioResponse desativar(UUID id, UUID quemPede) {
        if (id.equals(quemPede)) {
            throw new OperacaoNaoPermitidaException("Você não pode desativar o seu próprio login");
        }
        Usuario usuario = buscarDaEquipe(id);
        if (usuario.getPerfil() == Perfil.ADMIN) {
            List<Usuario> adminsAtivos = repository.travarAdminsAtivos();
            boolean quemPedeAindaEhAdminAtivo = adminsAtivos.stream().anyMatch(a -> a.getId().equals(quemPede));
            if (!quemPedeAindaEhAdminAtivo || adminsAtivos.size() <= 1) {
                throw new OperacaoNaoPermitidaException("A academia precisa de pelo menos um ADMIN ativo");
            }
        }
        usuario.desativar();
        return UsuarioResponse.de(usuario);
    }

    @Transactional
    UsuarioResponse reativar(UUID id) {
        Usuario usuario = buscarDaEquipe(id);
        if (!usuario.isAtivo()) {
            usuario.reativar();
        }
        return UsuarioResponse.de(usuario);
    }

    private Usuario buscarDaEquipe(UUID id) {
        Usuario usuario = repository.findById(id)
                .orElseThrow(() -> new UsuarioNaoEncontradoException("Usuário não encontrado"));
        if (usuario.getPerfil() == Perfil.ALUNO) {
            // O login do aluno acompanha o cadastro dele (ADR 0009); mexer só no login os desalinharia.
            throw new DadosInvalidosException(
                    "login de aluno acompanha o aluno: use /api/alunos/{id}/inativar ou /reativar");
        }
        return usuario;
    }

    record UsuarioCriado(Usuario usuario, String senhaTemporaria) {
    }
}
