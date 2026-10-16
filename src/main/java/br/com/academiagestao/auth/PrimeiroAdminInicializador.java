package br.com.academiagestao.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * Resolve o problema do "ovo e da galinha": para cadastrar usuários é preciso
 * estar logado como ADMIN, mas num banco novo ainda não existe nenhum.
 * Na inicialização, se não houver ADMIN, cria um com os dados do ambiente.
 */
@Component
class PrimeiroAdminInicializador implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PrimeiroAdminInicializador.class);

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AdminInicialProperties properties;

    PrimeiroAdminInicializador(UsuarioRepository repository,
                               PasswordEncoder passwordEncoder,
                               AdminInicialProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.existsByPerfil(Perfil.ADMIN)) {
            return;
        }
        if (!properties.configurado()) {
            log.warn("Nenhum ADMIN no banco e ADMIN_EMAIL/ADMIN_SENHA não definidos: "
                    + "ninguém conseguirá entrar no sistema. Veja o .env.example.");
            return;
        }
        validarSenha(properties.senha());

        String nome = properties.nome() == null || properties.nome().isBlank()
                ? "Administrador"
                : properties.nome();
        Usuario admin = Usuario.daEquipe(nome, properties.email(),
                passwordEncoder.encode(properties.senha()), Perfil.ADMIN);
        repository.save(admin);

        // Nunca logar a senha, nem o hash.
        log.info("Primeiro ADMIN criado: {}", admin.getEmail());
    }

    // Falhar na inicialização é melhor do que subir com um ADMIN de senha fraca.
    private static void validarSenha(String senha) {
        PoliticaDeSenha.problemaCom(senha).ifPresent(problema -> {
            throw new IllegalStateException("ADMIN_SENHA inválida: " + problema);
        });
    }
}
