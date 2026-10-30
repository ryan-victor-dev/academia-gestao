package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.Cpf;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Cadastro da equipe (ADMIN, RECEPCAO, INSTRUTOR). Logins de aluno ficam no módulo de alunos. */
@Service
class EquipeService {

    private static final String INDICE_DO_CPF = "uq_usuarios_cpf";

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final TrilhaDeUsuarios trilha;
    private final ExclusaoDeLogin exclusao;
    private final Clock relogio;

    EquipeService(UsuarioRepository repository, PasswordEncoder passwordEncoder, TrilhaDeUsuarios trilha,
                  ExclusaoDeLogin exclusao, Clock relogio) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.trilha = trilha;
        this.exclusao = exclusao;
        this.relogio = relogio;
    }

    /**
     * Os logins da equipe, pelo nome: são poucos. Os de aluno, que podem ser milhares, ficam de fora
     * e vêm paginados de {@link #listarLoginsDeAlunos} (diagnóstico de 27/10, B4).
     */
    @Transactional(readOnly = true)
    List<UsuarioResponse> listar() {
        return repository.findByPerfilNot(Perfil.ALUNO, Sort.by("nome")).stream()
                .map(UsuarioResponse::de)
                .toList();
    }

    /** Os logins de aluno, pelo nome, com busca opcional por qualquer parte do nome ou do e-mail. */
    @Transactional(readOnly = true)
    Page<Usuario> listarLoginsDeAlunos(String busca, int pagina, int tamanho) {
        String termo = busca == null ? "" : busca.trim().toLowerCase(Locale.ROOT);
        // Sem o escape, buscar "%" ou "_" casaria com todos (são curingas do LIKE).
        String padrao = "%" + termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return repository.buscarLoginsDeAlunos(padrao, PageRequest.of(pagina, tamanho, Sort.by("nome", "id")));
    }

    /**
     * O CPF (ADR 0047) e a data de nascimento (ADR 0048) são obrigatórios em todo login novo da equipe; quem já
     * existia fica sem até alguém preencher.
     */
    @Transactional
    UsuarioCriado criar(String nome, String email, String cpf, LocalDate dataNascimento, Perfil perfil) {
        if (perfil == Perfil.ALUNO) {
            throw new DadosInvalidosException("login de aluno é criado pelo cadastro do aluno: POST /api/alunos/{id}/login");
        }
        String emailNormalizado = Usuario.normalizarEmail(email);
        if (repository.existsByEmail(emailNormalizado)) {
            throw new LoginJaExisteException("Este e-mail já é usado por outro login");
        }
        if (repository.existsByCpf(Cpf.normalizar(cpf))) {
            throw new CpfDaEquipeEmUsoException();
        }

        String senhaTemporaria = GeradorDeSenhaTemporaria.gerar();
        Usuario usuario = Usuario.daEquipe(nome, emailNormalizado, passwordEncoder.encode(senhaTemporaria), perfil);
        usuario.informarCpf(cpf);
        usuario.informarNascimento(dataNascimento, relogio.instant());
        usuario.exigirTrocaDeSenha();
        try {
            repository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            barrarCpfRepetido(e);
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
     * Login da equipe criado por engano (ADR 0043): só sai o que nunca foi usado (nunca entrou,
     * não é autor na trilha, nada aponta para ele); usado, 409, e o caminho é desativar. Ninguém
     * exclui a si mesmo. A regra do último ADMIN não entra: quem pede é ADMIN e continua, e o ADMIN
     * excluído nunca entrou. A linha fica travada antes do retrato.
     */
    @Transactional
    void excluir(UUID id, UUID quemPede) {
        if (id.equals(quemPede)) {
            throw new OperacaoNaoPermitidaException("Você não pode excluir o seu próprio login");
        }
        Usuario usuario = repository.travarPorId(id)
                .orElseThrow(() -> new UsuarioNaoEncontradoException("Usuário não encontrado"));
        if (usuario.getPerfil() == Perfil.ALUNO) {
            throw new DadosInvalidosException("login de aluno acompanha o aluno: exclua o aluno (/api/alunos/{id})");
        }
        exclusao.excluir(usuario, "Usuário com histórico",
                "O usuário já entrou ou registrou algo e não pode ser excluído; desative-o");
    }

    /**
     * Muda nome, perfil, CPF e data de nascimento de alguém da equipe. Ninguém muda o próprio perfil, e
     * rebaixar um ADMIN passa pela mesma proteção da desativação (A rebaixa B enquanto B rebaixa A). Sem CPF
     * ou sem data no pedido, o atual fica: os dois se corrigem, mas não se apagam (ADRs 0047 e 0048).
     */
    @Transactional
    UsuarioResponse editar(UUID id, String nome, Perfil perfil, String cpf, LocalDate dataNascimento, UUID quemPede) {
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
        boolean mudaCpf = cpf != null && !cpf.isBlank() && !Cpf.normalizar(cpf).equals(usuario.getCpf());
        // Checa antes de alterar a entidade: a consulta faria o auto-flush do que já tivesse mudado.
        if (mudaCpf && repository.existsByCpf(Cpf.normalizar(cpf))) {
            throw new CpfDaEquipeEmUsoException();
        }
        Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
        usuario.renomear(nome);
        usuario.mudarPerfil(perfil);
        if (dataNascimento != null) {
            usuario.informarNascimento(dataNascimento, relogio.instant());
        }
        if (mudaCpf) {
            usuario.informarCpf(cpf);
            try {
                repository.flush();
            } catch (DataIntegrityViolationException e) {
                barrarCpfRepetido(e);
                throw e;
            }
        }
        trilha.alterado(usuario, "EDITADO", antes);
        return UsuarioResponse.de(usuario);
    }

    /** A própria pessoa da equipe informa ou corrige a sua data de nascimento (ADR 0048), como faz com o gênero. */
    @Transactional
    UsuarioResponse informarMeuNascimento(UUID id, LocalDate dataNascimento) {
        Usuario usuario = buscarDaEquipe(id);
        Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
        usuario.informarNascimento(dataNascimento, relogio.instant());
        trilha.alterado(usuario, "NASCIMENTO_INFORMADO", antes);
        return UsuarioResponse.de(usuario);
    }

    // A checagem do CPF dá a mensagem clara; numa corrida (dois cadastros com o mesmo CPF ao mesmo
    // tempo), quem barra é o índice único, e só o erro dele vira o 409 do CPF.
    private static void barrarCpfRepetido(DataIntegrityViolationException e) {
        if (e.getCause() instanceof ConstraintViolationException violacao
                && INDICE_DO_CPF.equals(violacao.getConstraintName())) {
            throw new CpfDaEquipeEmUsoException();
        }
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
