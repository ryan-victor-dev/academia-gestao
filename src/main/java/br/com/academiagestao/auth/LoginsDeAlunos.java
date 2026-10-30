package br.com.academiagestao.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
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
    private final TrilhaDeUsuarios trilha;
    private final ExclusaoDeLogin exclusao;

    LoginsDeAlunos(UsuarioRepository repository, PasswordEncoder passwordEncoder,
                   RedefinicaoDeSenha redefinicaoDeSenha, TrilhaDeUsuarios trilha, ExclusaoDeLogin exclusao) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.redefinicaoDeSenha = redefinicaoDeSenha;
        this.trilha = trilha;
        this.exclusao = exclusao;
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
        trilha.criado(usuario);
        return new CredenciaisTemporarias(usuario.getEmail(), senhaTemporaria);
    }

    /** O aluno esqueceu a senha: gera uma nova senha temporária para o login dele. */
    @Transactional
    public CredenciaisTemporarias redefinirSenha(UUID alunoId) {
        Usuario usuario = repository.findByAlunoId(alunoId)
                .orElseThrow(() -> new UsuarioNaoEncontradoException("Este aluno ainda não tem login"));
        return redefinicaoDeSenha.aplicar(usuario);
    }

    /**
     * Aluno inativado: o login dele (se existir) passa a ser só de leitura. Ele continua
     * vendo os próprios dados, direito dele (LGPD, art. 18; ADRs 0008 e 0029).
     */
    @Transactional
    public void restringirALeitura(UUID alunoId) {
        repository.findByAlunoId(alunoId).ifPresent(usuario -> {
            Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
            usuario.restringirALeitura();
            trilha.alterado(usuario, "SOMENTE_LEITURA", antes);
        });
    }

    /**
     * Aluno excluído (ADR 0043): o login dele (se existir) sai junto, desde que nunca tenha sido
     * usado. Usado, 409: o aluno que já entrou no app é inativado, e não excluído.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void excluir(UUID alunoId) {
        repository.findByAlunoId(alunoId).ifPresent(usuario -> exclusao.excluir(usuario, "Aluno com histórico",
                "O aluno já entrou no app e não pode ser excluído; inative-o"));
    }

    public boolean temLogin(UUID alunoId) {
        return repository.existsByAlunoId(alunoId);
    }

    /**
     * O cadastro do aluno foi editado (ADR 0045): o login dele (se existir) passa a usar o e-mail do
     * cadastro, a confirmar. Compara com o do login, e não com o anterior do cadastro: um login que
     * ficou para trás (antes desta regra) volta a acompanhar na próxima edição.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acompanharEmail(UUID alunoId, String email) {
        String normalizado = Usuario.normalizarEmail(email);
        repository.findByAlunoId(alunoId).filter(usuario -> !usuario.getEmail().equals(normalizado)).ifPresent(usuario -> {
            // Antes de alterar a entidade: a consulta dispara o flush do Hibernate (CLAUDE.md).
            if (repository.existsByEmail(normalizado)) {
                throw new LoginJaExisteException("Este e-mail já é usado por outro login");
            }
            Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
            usuario.acompanharEmailDoCadastro(normalizado);
            try {
                repository.saveAndFlush(usuario);
            } catch (DataIntegrityViolationException e) {
                // Outro login pegou o e-mail ao mesmo tempo: o UNIQUE do banco barra.
                throw new LoginJaExisteException("Este e-mail já é usado por outro login");
            }
            trilha.alterado(usuario, "EMAIL_DO_CADASTRO", antes);
        });
    }

    /** Aluno reativado: o login dele (se existir) volta ao uso normal. */
    @Transactional
    public void liberarDaLeitura(UUID alunoId) {
        repository.findByAlunoId(alunoId).ifPresent(usuario -> {
            Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
            usuario.liberarDaLeitura();
            trilha.alterado(usuario, "LIBERADO", antes);
        });
    }
}
