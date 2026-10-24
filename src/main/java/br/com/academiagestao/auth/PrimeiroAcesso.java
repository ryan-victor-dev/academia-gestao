package br.com.academiagestao.auth;

import br.com.academiagestao.auth.CodigosDeEmail.Finalidade;
import br.com.academiagestao.auth.CodigosDeEmail.Resultado;
import br.com.academiagestao.compartilhado.CadastroParaPrimeiroAcesso;
import br.com.academiagestao.compartilhado.CadastroParaPrimeiroAcesso.AlunoDoCadastro;
import br.com.academiagestao.compartilhado.Correio;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Primeiro acesso do aluno (decisões do dono, ADR 0039): o aluno ativo, ainda sem login, digita o
 * CPF e o e-mail do cadastro, recebe um código de 6 dígitos nesse e-mail e cria a própria senha. O
 * login já nasce com o e-mail confirmado. Como no "esqueci a senha" (ADR 0038), nada revela quem é
 * aluno: a resposta é a mesma, o tempo também, e os limites são os do login, pelo e-mail digitado.
 */
@Service
class PrimeiroAcesso {

    private static final Logger log = LoggerFactory.getLogger(PrimeiroAcesso.class);

    private final CadastroParaPrimeiroAcesso cadastro;
    private final UsuarioRepository repository;
    private final CodigosDeEmail codigos;
    private final Correio correio;
    private final PasswordEncoder passwordEncoder;
    private final LimitadorDeTentativas limitador;
    private final TrilhaDeUsuarios trilha;
    private final Clock relogio;
    private final TransactionTemplate transacao;
    private final Executor envio;

    PrimeiroAcesso(CadastroParaPrimeiroAcesso cadastro, UsuarioRepository repository, CodigosDeEmail codigos,
                   Correio correio, PasswordEncoder passwordEncoder, LimitadorDeTentativas limitador,
                   TrilhaDeUsuarios trilha, Clock relogio, PlatformTransactionManager transacoes,
                   @Qualifier("envioDeEmail") Executor envio) {
        this.cadastro = cadastro;
        this.repository = repository;
        this.codigos = codigos;
        this.correio = correio;
        this.passwordEncoder = passwordEncoder;
        this.limitador = limitador;
        this.trilha = trilha;
        this.relogio = relogio;
        this.transacao = new TransactionTemplate(transacoes);
        this.envio = envio;
    }

    /** A tela de login mostra o "primeiro acesso" só com o e-mail ligado. */
    boolean disponivel() {
        return correio.ligado();
    }

    /**
     * O aluno que pode: ativo (decisão do dono), com este CPF e este e-mail no cadastro, ainda sem
     * login, e com um e-mail que nenhum outro login usa (senão o login não poderia ser criado).
     */
    private Optional<AlunoDoCadastro> quemPode(String cpf, String email) {
        return cadastro.ativoCom(cpf, email)
                .filter(aluno -> !repository.existsByAlunoId(aluno.id()))
                .filter(aluno -> !repository.existsByEmail(aluno.email()));
    }

    /** Manda o código, se o CPF e o e-mail forem de quem pode. A resposta é sempre a mesma. */
    void pedir(String cpf, String email, String ip) {
        exigirLigado();
        String conta = Usuario.normalizarEmail(email);
        limitador.verificarERegistrar(ip, conta);
        Optional<AlunoDoCadastro> aluno = quemPode(cpf, conta);
        if (aluno.isEmpty()) {
            // O mesmo BCrypt de quem pode: o tempo de resposta não conta quem é aluno.
            passwordEncoder.encode("000000");
            log.info("Primeiro acesso: nenhum aluno sem login com estes dados (conta {} / IP {})", conta, ip);
            return;
        }
        AlunoDoCadastro a = aluno.get();
        String codigo;
        try {
            codigo = transacao.execute(t -> codigos.emitir(a.id(), Finalidade.PRIMEIRO_ACESSO, a.email()));
        } catch (MuitosCodigosException e) {
            // Responder 429 só para quem é aluno revelaria o cadastro: fica no log, e nada sai.
            log.warn("Primeiro acesso: muitos códigos pedidos para a conta {}", conta);
            return;
        }
        envio.execute(() -> {
            try {
                correio.enviar(new Correio.Mensagem(a.email(), "Código para criar o seu acesso",
                        "Olá, " + a.nome() + "!\n\nSeu código para criar a senha do seu acesso à academia é: " + codigo
                                + "\n\nEle vale 15 minutos. Se você não pediu, ignore este e-mail."));
            } catch (RuntimeException e) {
                log.warn("Primeiro acesso: o e-mail não saiu para a conta {}: {}", conta, e.getMessage());
            }
        });
    }

    /**
     * O código confere: o login do aluno nasce com a senha escolhida e o e-mail confirmado (o
     * código chegou nele). Código errado conta como senha errada no limite do login.
     */
    void criarSenha(String cpf, String email, String codigo, String senha, String ip) {
        exigirLigado();
        PoliticaDeSenha.problemaCom(senha).ifPresent(problema -> {
            throw new DadosInvalidosException(problema);
        });
        String conta = Usuario.normalizarEmail(email);
        limitador.verificarERegistrar(ip, conta);
        Optional<AlunoDoCadastro> aluno = quemPode(cpf, conta);
        Resultado resultado = aluno
                .map(a -> codigos.conferir(a.id(), Finalidade.PRIMEIRO_ACESSO, a.email(), codigo))
                .orElse(Resultado.NAO_CONFERE);
        if (resultado != Resultado.CONFERE) {
            limitador.registrarFalha(conta, ip);
            log.warn("Primeiro acesso recusado (código): conta {} / IP {}", conta, ip);
            throw new CodigoInvalidoException();
        }
        AlunoDoCadastro a = aluno.get();
        String hash = passwordEncoder.encode(senha);
        try {
            transacao.executeWithoutResult(t -> {
                // A recepção pode ter criado o login, ou mudado o cadastro, depois do código.
                if (quemPode(cpf, conta).isEmpty()) {
                    throw new LoginJaExisteException("O cadastro mudou ou o login já existe: entre ou procure a recepção");
                }
                Usuario usuario = Usuario.deAluno(a.nome(), a.email(), hash, a.id());
                usuario.confirmarEmail(a.email(), relogio.instant());
                repository.saveAndFlush(usuario);
                trilha.alterado(usuario, "CRIADO_NO_PRIMEIRO_ACESSO", Map.of());
            });
        } catch (DataIntegrityViolationException e) {
            // A recepção criou o login ao mesmo tempo: o UNIQUE do banco barra o segundo.
            throw new LoginJaExisteException("O login já existe: entre ou procure a recepção");
        }
        limitador.registrarSucesso(conta, ip);
        log.info("Primeiro acesso: login criado pelo aluno (conta {} / IP {})", conta, ip);
    }

    private void exigirLigado() {
        if (!correio.ligado()) {
            throw new OperacaoNaoPermitidaException("O primeiro acesso por e-mail está desligado: peça o acesso à recepção");
        }
    }
}
