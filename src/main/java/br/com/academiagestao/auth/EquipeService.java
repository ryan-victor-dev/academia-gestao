package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cadastro da equipe (ADMIN, RECEPCAO, INSTRUTOR). Logins de aluno ficam no módulo de alunos. */
@Service
class EquipeService {

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final TrilhaDeUsuarios trilha;

    EquipeService(UsuarioRepository repository, PasswordEncoder passwordEncoder, TrilhaDeUsuarios trilha) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.trilha = trilha;
    }

    /** Todos os logins do sistema, também os de aluno, pelo nome. */
    @Transactional(readOnly = true)
    List<UsuarioResponse> listar() {
        return repository.findAll(Sort.by("nome")).stream()
                .map(UsuarioResponse::de)
                .toList();
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
        trilha.criado(usuario);
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
            garantirQueSobraOutroAdmin(quemPede);
        }
        Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
        usuario.desativar();
        trilha.alterado(usuario, "DESATIVADO", antes);
        return UsuarioResponse.de(usuario);
    }

    /**
     * Muda nome e perfil de alguém da equipe. Ninguém muda o próprio perfil, e rebaixar um
     * ADMIN passa pela mesma proteção da desativação (A rebaixa B enquanto B rebaixa A).
     */
    @Transactional
    UsuarioResponse editar(UUID id, String nome, Perfil perfil, UUID quemPede) {
        if (perfil == Perfil.ALUNO) {
            throw new DadosInvalidosException("o perfil ALUNO não é dado por aqui: login de aluno nasce do cadastro do aluno");
        }
        Usuario usuario = buscarDaEquipe(id);
        boolean mudaPerfil = usuario.getPerfil() != perfil;
        if (mudaPerfil && id.equals(quemPede)) {
            throw new OperacaoNaoPermitidaException("Você não pode mudar o seu próprio perfil");
        }
        if (mudaPerfil && usuario.getPerfil() == Perfil.ADMIN) {
            garantirQueSobraOutroAdmin(quemPede);
        }
        Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
        usuario.renomear(nome);
        usuario.mudarPerfil(perfil);
        trilha.alterado(usuario, "EDITADO", antes);
        return UsuarioResponse.de(usuario);
    }

    // Trava as linhas dos ADMINs ativos (SELECT ... FOR UPDATE) e só então conta: quem pede
    // precisa continuar sendo ADMIN ativo e sobrar pelo menos um além do afetado.
    private void garantirQueSobraOutroAdmin(UUID quemPede) {
        List<Usuario> adminsAtivos = repository.travarAdminsAtivos();
        boolean quemPedeAindaEhAdminAtivo = adminsAtivos.stream().anyMatch(a -> a.getId().equals(quemPede));
        if (!quemPedeAindaEhAdminAtivo || adminsAtivos.size() <= 1) {
            throw new OperacaoNaoPermitidaException("A academia precisa de pelo menos um ADMIN ativo");
        }
    }

    @Transactional
    UsuarioResponse reativar(UUID id) {
        Usuario usuario = buscarDaEquipe(id);
        Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
        if (!usuario.isAtivo()) {
            usuario.reativar();
        }
        trilha.alterado(usuario, "REATIVADO", antes);
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
