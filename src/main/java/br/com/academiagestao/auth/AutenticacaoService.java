package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
class AutenticacaoService {

    private static final Logger log = LoggerFactory.getLogger(AutenticacaoService.class);

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final GeradorDeToken geradorDeToken;
    private final LimitadorDeTentativas limitador;

    // Hash de uma senha que ninguém conhece. Quando o e-mail não existe, comparamos a senha
    // com ele mesmo assim, para a resposta demorar o mesmo tanto que com um e-mail válido.
    // Sem isso, medir o tempo de resposta revelaria quais e-mails estão cadastrados.
    private final String hashFalso;

    AutenticacaoService(UsuarioRepository repository,
                        PasswordEncoder passwordEncoder,
                        GeradorDeToken geradorDeToken,
                        LimitadorDeTentativas limitador) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.geradorDeToken = geradorDeToken;
        this.limitador = limitador;
        this.hashFalso = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Confere e-mail e senha. Os limites contra força bruta valem pelo e-mail digitado,
     * exista ele ou não: o bloqueio também não pode revelar quais e-mails estão cadastrados.
     */
    @Transactional(readOnly = true)
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
            limitador.registrarFalha(conta);
            // Nunca a senha digitada: ela pode ser a senha certa de outra conta, com um erro de digitação.
            log.warn("Login recusado: conta {} / IP {}", conta, ip);
            throw new CredenciaisInvalidasException();
        }
        limitador.registrarSucesso(conta);
        log.info("Login: conta {} / IP {}", conta, ip);
        return geradorDeToken.gerar(usuario.get());
    }

    @Transactional
    GeradorDeToken.TokenEmitido trocarSenha(UUID usuarioId, String senhaAtual, String novaSenha) {
        // O token pode ser de alguém desativado depois do login: aí, nada de trocar senha.
        Usuario usuario = repository.findById(usuarioId)
                .filter(Usuario::isAtivo)
                .orElseThrow(() -> new DisabledException("Usuário inativo ou removido"));

        if (!passwordEncoder.matches(senhaAtual, usuario.getSenhaHash())) {
            throw new DadosInvalidosException("senha atual incorreta");
        }
        PoliticaDeSenha.problemaCom(novaSenha).ifPresent(problema -> {
            throw new DadosInvalidosException(problema);
        });
        if (novaSenha.equals(senhaAtual)) {
            throw new DadosInvalidosException("a nova senha precisa ser diferente da atual");
        }

        usuario.trocarSenha(passwordEncoder.encode(novaSenha));
        return geradorDeToken.gerar(usuario);
    }
}
