package br.com.academiagestao.auth;

import br.com.academiagestao.auth.CodigosDeEmail.Finalidade;
import br.com.academiagestao.auth.CodigosDeEmail.Resultado;
import br.com.academiagestao.compartilhado.Correio;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * "Esqueci a senha" (decisões do dono, ADR 0038): um código de 6 dígitos no e-mail troca a senha
 * de quem tem o e-mail confirmado, menos o ADMIN. Nada aqui revela se um e-mail está cadastrado:
 * a resposta é a mesma, o tempo também (o e-mail sai em segundo plano), e os limites são os do
 * login, pelo e-mail digitado.
 */
@Service
class RecuperacaoDeSenha {

    private static final Logger log = LoggerFactory.getLogger(RecuperacaoDeSenha.class);

    private final UsuarioRepository repository;
    private final CodigosDeEmail codigos;
    private final Correio correio;
    private final PasswordEncoder passwordEncoder;
    private final LimitadorDeTentativas limitador;
    private final TrilhaDeUsuarios trilha;
    private final TransactionTemplate transacao;
    private final Executor envio;

    RecuperacaoDeSenha(UsuarioRepository repository, CodigosDeEmail codigos, Correio correio,
                       PasswordEncoder passwordEncoder, LimitadorDeTentativas limitador, TrilhaDeUsuarios trilha,
                       PlatformTransactionManager transacoes, @Qualifier("envioDeEmail") Executor envio) {
        this.repository = repository;
        this.codigos = codigos;
        this.correio = correio;
        this.passwordEncoder = passwordEncoder;
        this.limitador = limitador;
        this.trilha = trilha;
        this.transacao = new TransactionTemplate(transacoes);
        this.envio = envio;
    }

    /** A tela de login mostra o "esqueci a senha" só com o e-mail ligado. */
    boolean disponivel() {
        return correio.ligado();
    }

    /** Quem pode: ativo, e-mail confirmado (um e-mail errado não entrega a conta a um estranho), não ADMIN. */
    static boolean podeRecuperar(Usuario usuario) {
        return usuario.isAtivo() && usuario.getPerfil() != Perfil.ADMIN && usuario.getEmailConfirmadoEm() != null;
    }

    /** Manda o código, se o e-mail for de quem pode recuperar. A resposta é sempre a mesma. */
    void pedir(String email, String ip) {
        exigirLigado();
        String conta = Usuario.normalizarEmail(email);
        limitador.verificarERegistrar(ip, conta);
        Optional<Usuario> usuario = repository.findByEmail(conta).filter(RecuperacaoDeSenha::podeRecuperar);
        if (usuario.isEmpty()) {
            // O mesmo BCrypt de quem existe: o tempo de resposta não conta quem está cadastrado.
            passwordEncoder.encode("000000");
            return;
        }
        Usuario u = usuario.get();
        String codigo;
        try {
            codigo = transacao.execute(t -> codigos.emitir(u.getId(), Finalidade.RECUPERACAO, u.getEmail()));
        } catch (MuitosCodigosException e) {
            // Responder 429 só para quem existe revelaria a conta: fica no log, e nada sai. O código
            // é recusado antes do BCrypt; sem o falso, a resposta rápida contaria que a conta existe.
            passwordEncoder.encode("000000");
            log.warn("Recuperação de senha: muitos códigos pedidos para a conta {}", conta);
            return;
        }
        String nome = u.getNome();
        envio.execute(() -> {
            try {
                correio.enviar(new Correio.Mensagem(u.getEmail(), "Código para criar uma nova senha",
                        "Olá, " + nome + "!\n\nSeu código para criar uma nova senha é: " + codigo
                                + "\n\nEle vale 15 minutos. Se você não pediu, ignore este e-mail: sua senha não muda."));
            } catch (RuntimeException e) {
                log.warn("Recuperação de senha: o e-mail não saiu para a conta {}: {}", conta, e.getMessage());
            }
        });
    }

    /**
     * O código confere: a senha nova vale, as sessões abertas caem (versão do token), e quem entra
     * depois usa a senha nova. Código errado conta como senha errada no limite do login.
     */
    void redefinir(String email, String codigo, String novaSenha, String ip) {
        exigirLigado();
        PoliticaDeSenha.problemaCom(novaSenha).ifPresent(problema -> {
            throw new DadosInvalidosException(problema);
        });
        String conta = Usuario.normalizarEmail(email);
        limitador.verificarERegistrar(ip, conta);
        Optional<Usuario> usuario = repository.findByEmail(conta).filter(RecuperacaoDeSenha::podeRecuperar);
        Resultado resultado = usuario
                .map(u -> codigos.conferir(u.getId(), Finalidade.RECUPERACAO, u.getEmail(), codigo))
                .orElse(Resultado.NAO_CONFERE);
        if (resultado != Resultado.CONFERE) {
            limitador.registrarFalha(conta, ip);
            log.warn("Recuperação de senha recusada (código): conta {} / IP {}", conta, ip);
            throw new CodigoInvalidoException();
        }
        UUID id = usuario.get().getId();
        String hash = passwordEncoder.encode(novaSenha);
        transacao.executeWithoutResult(t -> {
            Usuario paraGravar = repository.findById(id).orElseThrow();
            paraGravar.trocarSenha(hash);
            trilha.ato(paraGravar, "SENHA_RECUPERADA");
        });
        limitador.registrarSucesso(conta, ip);
        log.info("Senha recuperada por e-mail: conta {} / IP {}", conta, ip);
    }

    private void exigirLigado() {
        if (!correio.ligado()) {
            throw new OperacaoNaoPermitidaException("A recuperação de senha por e-mail está desligada: peça à recepção");
        }
    }
}
