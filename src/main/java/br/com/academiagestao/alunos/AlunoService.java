package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.Cpf;
import br.com.academiagestao.auth.CredenciaisTemporarias;
import br.com.academiagestao.auth.LoginsDeAlunos;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import br.com.academiagestao.compartilhado.EmailDoAlunoCorrigido;
import br.com.academiagestao.compartilhado.ExclusaoRecusadaException;
import br.com.academiagestao.compartilhado.RecursoModificadoException;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.ALUNO;

@Service
class AlunoService {

    private static final String INDICE_DO_CPF = "alunos_cpf_key";

    private final AlunoRepository repository;
    private final LoginsDeAlunos loginsDeAlunos;
    private final List<ImpedimentoParaInativar> impedimentos;
    private final List<AoMudarASituacaoDoAluno> interessados;
    private final Auditoria auditoria;
    private final Clock relogio;

    AlunoService(AlunoRepository repository, LoginsDeAlunos loginsDeAlunos,
                 List<ImpedimentoParaInativar> impedimentos, List<AoMudarASituacaoDoAluno> interessados,
                 Auditoria auditoria, Clock relogio) {
        this.repository = repository;
        this.loginsDeAlunos = loginsDeAlunos;
        this.impedimentos = impedimentos;
        this.interessados = interessados;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @Transactional
    Aluno cadastrar(DadosAluno dados) {
        Aluno aluno = Aluno.novo(dados, relogio.instant());
        if (repository.existsByCpf(aluno.getCpf())) {
            throw new CpfJaCadastradoException();
        }
        Aluno gravado = gravar(aluno);
        auditoria.registrar(ALUNO, gravado.getId(), "CRIADO", Map.of(), retrato(gravado));
        return gravado;
    }

    @Transactional(readOnly = true)
    Aluno buscar(UUID id) {
        return repository.findById(id).orElseThrow(() -> new AlunoNaoEncontradoException(id));
    }

    @Transactional(readOnly = true)
    Page<Aluno> listar(String busca, boolean incluirInativos, boolean buscaPorCpf, int pagina, int tamanho) {
        // Ordem fixa por nome (e id, para empates não trocarem de página entre consultas).
        PageRequest pageRequest = PageRequest.of(pagina, tamanho, Sort.by("nome", "id"));
        return repository.findAll(AlunoRepository.filtro(busca, incluirInativos, buscaPorCpf), pageRequest);
    }

    /**
     * O login usa o e-mail do cadastro do aluno, e nasce com senha temporária. A linha do aluno
     * fica travada: uma exclusão ao mesmo tempo espera, ou é esperada (ADR 0043).
     */
    @Transactional
    CredenciaisTemporarias criarLogin(UUID id) {
        Aluno aluno = repository.travarPorId(id).orElseThrow(() -> new AlunoNaoEncontradoException(id));
        if (!aluno.isAtivo()) {
            throw new AlunoInativoException();
        }
        if (aluno.getEmail() == null) {
            throw new DadosInvalidosException("cadastre um e-mail no aluno antes de criar o login");
        }
        return loginsDeAlunos.criar(aluno.getId(), aluno.getNome(), aluno.getEmail());
    }

    // Aluno e login mudam na mesma transação: ou os dois mudam, ou nenhum.
    /** O aluno esqueceu a senha. Aluno inativo não recebe senha nova: reative antes. */
    @Transactional
    CredenciaisTemporarias redefinirSenha(UUID id) {
        Aluno aluno = buscar(id);
        if (!aluno.isAtivo()) {
            throw new AlunoInativoException();
        }
        return loginsDeAlunos.redefinirSenha(id);
    }

    /**
     * Recusa (409) se outro módulo tiver um impedimento, como uma matrícula vigente. A linha
     * do aluno fica travada antes de perguntar: uma matrícula feita ao mesmo tempo espera esta
     * transação (e vê o aluno inativo), ou esta espera a matrícula (e vê o impedimento).
     */
    @Transactional
    Aluno inativar(UUID id) {
        Aluno aluno = repository.travarPorId(id).orElseThrow(() -> new AlunoNaoEncontradoException(id));
        for (ImpedimentoParaInativar impedimento : impedimentos) {
            Optional<String> motivo = impedimento.verificar(id);
            if (motivo.isPresent()) {
                throw new InativacaoImpedidaException(motivo.get());
            }
        }
        Map<String, Object> antes = retrato(aluno);
        aluno.inativar();
        loginsDeAlunos.restringirALeitura(id);
        interessados.forEach(i -> i.inativado(id));
        auditoria.registrar(ALUNO, id, "INATIVADO", antes, retrato(aluno));
        return aluno;
    }

    @Transactional
    Aluno reativar(UUID id) {
        Aluno aluno = buscar(id);
        Map<String, Object> antes = retrato(aluno);
        aluno.reativar();
        loginsDeAlunos.liberarDaLeitura(id);
        interessados.forEach(i -> i.reativado(id));
        auditoria.registrar(ALUNO, id, "REATIVADO", antes, retrato(aluno));
        return aluno;
    }

    /**
     * Só o aluno que nunca foi usado sai (cadastrado por engano, ADR 0043), com o login dele, se
     * nunca entrou. Quem barra é a chave estrangeira de matrículas, cobranças, fichas, pagamentos e
     * códigos do primeiro acesso: com histórico, 409, e o caminho é inativar. A linha fica
     * travada, como na inativação: matrícula, ficha e login ao mesmo tempo esperam, ou são esperados.
     */
    @Transactional
    void excluir(UUID id) {
        Aluno aluno = repository.travarPorId(id).orElseThrow(() -> new AlunoNaoEncontradoException(id));
        Map<String, Object> antes = retrato(aluno);
        loginsDeAlunos.excluir(id);
        try {
            repository.delete(aluno);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            if (!ExclusaoRecusadaException.temHistorico(e)) {
                throw e;
            }
            throw new ExclusaoRecusadaException("Aluno com histórico",
                    "O aluno já tem matrícula, ficha, pagamento ou pedido de primeiro acesso e não pode ser excluído; inative-o");
        }
        auditoria.registrar(ALUNO, id, "EXCLUIDO", antes, Map.of());
    }

    /** Só altera se o If-Match bater com a versão atual do aluno (ver VersaoEsperada). */
    @Transactional
    Aluno atualizar(UUID id, DadosAluno dados, String ifMatch) {
        Aluno aluno = buscar(id);
        VersaoEsperada.conferir(ifMatch, aluno.getVersao(), "o aluno");
        String cpfNovo = Cpf.normalizar(dados.cpf());
        if (!cpfNovo.equals(aluno.getCpf()) && repository.existsByCpf(cpfNovo)) {
            throw new CpfJaCadastradoException();
        }
        // O login usa o e-mail do cadastro (ADR 0045): sem ele, o aluno não entraria nem receberia o código.
        boolean tiraOEmail = aluno.getEmail() != null && (dados.email() == null || dados.email().isBlank());
        if (tiraOEmail && loginsDeAlunos.temLogin(id)) {
            throw new DadosInvalidosException("o aluno tem login: o e-mail não pode ficar vazio");
        }
        Map<String, Object> antes = retrato(aluno);
        aluno.atualizar(dados, relogio.instant());
        Aluno gravado = gravar(aluno);
        if (gravado.getEmail() != null) {
            // Depois do gravar: o aluno já foi ao banco, e a consulta do login não dispara nada dele.
            loginsDeAlunos.acompanharEmail(id, gravado.getEmail());
        }
        auditoria.registrar(ALUNO, id, "EDITADO", antes, retrato(gravado));
        return gravado;
    }

    /**
     * O aluno corrigiu o e-mail do login no primeiro acesso (ADR 0038): o cadastro acompanha, na
     * transação de quem corrigiu, e a trilha registra.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void aoCorrigirEmail(EmailDoAlunoCorrigido evento) {
        Aluno aluno = buscar(evento.alunoId());
        Map<String, Object> antes = retrato(aluno);
        aluno.corrigirEmail(evento.email());
        Aluno gravado = gravar(aluno);
        auditoria.registrar(ALUNO, aluno.getId(), "EMAIL_CORRIGIDO", antes, retrato(gravado));
    }

    // Dados pessoais: a trilha guarda o valor anterior (por isso só o ADMIN a consulta).
    private static Map<String, Object> retrato(Aluno aluno) {
        return Auditoria.campos("nome", aluno.getNome(), "cpf", aluno.getCpf(), "email", aluno.getEmail(),
                "telefone", aluno.getTelefone(), "dataNascimento", aluno.getDataNascimento(),
                "observacoes", aluno.getObservacoes(), "ativo", aluno.isAtivo());
    }

    // A checagem existsByCpf dá uma mensagem clara no caso comum. Mas duas pessoas
    // cadastrando o mesmo CPF ao mesmo tempo passariam as duas pela checagem; aí quem
    // barra é o UNIQUE do banco, e o erro dele vira o mesmo 409. Só o dele: antes, qualquer
    // recusa do banco (como uma data fora do que ele guarda) virava "CPF já cadastrado".
    private Aluno gravar(Aluno aluno) {
        try {
            return repository.saveAndFlush(aluno);
        } catch (DataIntegrityViolationException e) {
            if (!(e.getCause() instanceof ConstraintViolationException violacao)
                    || !INDICE_DO_CPF.equals(violacao.getConstraintName())) {
                throw e;
            }
            throw new CpfJaCadastradoException();
        } catch (ObjectOptimisticLockingFailureException e) {
            // Duas edições com o mesmo If-Match ao mesmo tempo: as duas passam pela
            // conferência acima, mas só o primeiro UPDATE acha a versão; o segundo cai aqui.
            throw new RecursoModificadoException("o aluno");
        }
    }
}
