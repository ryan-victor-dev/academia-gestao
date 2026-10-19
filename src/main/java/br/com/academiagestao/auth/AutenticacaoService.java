package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Login e troca de senha. Nenhum passo segura mais de uma conexão com o banco, e nenhuma
 * conexão fica presa durante o BCrypt (ADR 0020): antes, o login abria uma transação e o
 * limitador, que grava na sua própria, pedia uma segunda conexão; com ~10 logins ao mesmo
 * tempo, as 10 conexões do pool ficavam presas esperando e o sistema inteiro parava.
 */
@Service
class AutenticacaoService {

    private static final Logger log = LoggerFactory.getLogger(AutenticacaoService.class);

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final GeradorDeToken geradorDeToken;
    private final LimitadorDeTentativas limitador;
    private final TransactionTemplate transacao;
    private final TrilhaDeUsuarios trilha;

    // Hash de uma senha que ninguém conhece. Quando o e-mail não existe, comparamos a senha
    // com ele mesmo assim, para a resposta demorar o mesmo tanto que com um e-mail válido.
    // Sem isso, medir o tempo de resposta revelaria quais e-mails estão cadastrados.
    private final String hashFalso;

    AutenticacaoService(UsuarioRepository repository,
                        PasswordEncoder passwordEncoder,
                        GeradorDeToken geradorDeToken,
                        LimitadorDeTentativas limitador,
                        PlatformTransactionManager transacoes,
                        TrilhaDeUsuarios trilha) {
        this.repository = repository;
        this.trilha = trilha;
        this.passwordEncoder = passwordEncoder;
        this.geradorDeToken = geradorDeToken;
        this.limitador = limitador;
        this.transacao = new TransactionTemplate(transacoes);
        this.hashFalso = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Confere e-mail e senha. Os limites contra força bruta valem pelo e-mail digitado,
     * exista ele ou não: o bloqueio também não pode revelar quais e-mails estão cadastrados.
     * As falhas bloqueiam a conta só para o IP de onde vieram (e para todos, acima de um teto
     * bem maior): quem sabe só o e-mail não tranca o dono para fora.
     * Sem transação aqui: cada consulta usa a sua, curta, e o BCrypt roda sem conexão presa.
     */
    GeradorDeToken.TokenEmitido autenticar(String email, String senha, String ip) {
        String conta = Usuario.normalizarEmail(email);
        try {
            limitador.verificarERegistrar(ip, conta);
        } catch (MuitasTentativasException e) {
            log.warn("Login bloqueado por excesso de tentativas: conta {} / IP {}", conta, ip);
            throw e;
        }

        Optional<Usuario> usuario = repository.findByEmail(conta);
        // A senha é sempre conferida, mesmo para usuário inexistente ou inativo (mesmo tempo).
        String hash = usuario.map(Usuario::getSenhaHash).orElse(hashFalso);
        boolean senhaConfere = passwordEncoder.matches(senha, hash);

        if (usuario.isEmpty() || !senhaConfere || !usuario.get().isAtivo()) {
            limitador.registrarFalha(conta, ip);
            // Nunca a senha digitada: ela pode ser a senha certa de outra conta, com um erro de digitação.
            log.warn("Login recusado: conta {} / IP {}", conta, ip);
            throw new CredenciaisInvalidasException();
        }
        limitador.registrarSucesso(conta, ip);
        log.info("Login: conta {} / IP {}", conta, ip);
        return geradorDeToken.gerar(usuario.get());
    }

    /**
     * Troca a senha do dono do token. A senha atual passa pelo mesmo limite de tentativas
     * do login (mesma conta): sem isso, quem roubasse um token poderia descobrir a senha
     * atual por tentativa e erro e ganhar acesso permanente, já que o token dura 15 minutos.
     * Conferências e BCrypt fora de transação; só a gravação final abre uma, curta.
     */
    GeradorDeToken.TokenEmitido trocarSenha(UUID usuarioId, String senhaAtual, String novaSenha, String ip) {
        // O token pode ser de alguém desativado depois do login: aí, nada de trocar senha.
        Usuario usuario = repository.findById(usuarioId)
                .filter(Usuario::isAtivo)
                .orElseThrow(() -> new DisabledException("Usuário inativo ou removido"));

        String conta = usuario.getEmail();
        try {
            limitador.verificarERegistrar(ip, conta);
        } catch (MuitasTentativasException e) {
            log.warn("Troca de senha bloqueada por excesso de tentativas: conta {} / IP {}", conta, ip);
            throw e;
        }
        if (!passwordEncoder.matches(senhaAtual, usuario.getSenhaHash())) {
            limitador.registrarFalha(conta, ip);
            log.warn("Troca de senha recusada (senha atual errada): conta {} / IP {}", conta, ip);
            throw new DadosInvalidosException("senha atual incorreta");
        }
        limitador.registrarSucesso(conta, ip);
        PoliticaDeSenha.problemaCom(novaSenha).ifPresent(problema -> {
            throw new DadosInvalidosException(problema);
        });
        if (novaSenha.equals(senhaAtual)) {
            throw new DadosInvalidosException("a nova senha precisa ser diferente da atual");
        }

        String novoHash = passwordEncoder.encode(novaSenha);
        String hashConferido = usuario.getSenhaHash();
        Usuario atualizado = transacao.execute(status -> {
            Usuario paraGravar = repository.findById(usuarioId)
                    .filter(Usuario::isAtivo)
                    .orElseThrow(() -> new DisabledException("Usuário inativo ou removido"));
            // A senha mudou (ou foi redefinida) entre a conferência e aqui: a atual digitada
            // já não vale.
            if (!paraGravar.getSenhaHash().equals(hashConferido)) {
                throw new DadosInvalidosException("senha atual incorreta");
            }
            paraGravar.trocarSenha(novoHash);
            Usuario gravado = repository.saveAndFlush(paraGravar);
            trilha.ato(gravado, "SENHA_TROCADA");
            return gravado;
        });
        return geradorDeToken.gerar(atualizado);
    }
}
