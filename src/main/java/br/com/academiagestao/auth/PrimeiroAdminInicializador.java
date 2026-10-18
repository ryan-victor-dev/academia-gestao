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
    private final TrilhaDeUsuarios trilha;
    private final AdminInicialProperties properties;

    PrimeiroAdminInicializador(UsuarioRepository repository,
                               PasswordEncoder passwordEncoder,
                               AdminInicialProperties properties,
                               TrilhaDeUsuarios trilha) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.trilha = trilha;
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
        // A senha do .env fica no servidor (no arquivo e nas variáveis do container). Ela
        // vale só para o primeiro acesso: depois de trocada, um .env vazado não entrega o ADMIN.
        admin.exigirTrocaDeSenha();
        repository.save(admin);
        trilha.criado(admin);  // pelo sistema: não há ninguém logado

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
