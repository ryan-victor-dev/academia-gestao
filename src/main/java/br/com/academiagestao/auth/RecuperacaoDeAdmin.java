package br.com.academiagestao.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Recuperação de um ADMIN que esqueceu a senha, por comando no servidor:
 *
 *   docker compose run --rm app --recuperar-admin=admin@academia.local
 *
 * Quem consegue rodar isso já tem acesso ao servidor e ao banco: essa é a prova de
 * autoridade. Nada disso fica exposto na API. A senha temporária vai só para o
 * terminal (saída padrão), nunca para o log, e exige troca no próximo acesso.
 */
@Component
public class RecuperacaoDeAdmin implements ApplicationRunner, ExitCodeGenerator {

    public static final String OPCAO = "recuperar-admin";

    private static final Logger log = LoggerFactory.getLogger(RecuperacaoDeAdmin.class);

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final TrilhaDeUsuarios trilha;
    private PrintStream saida = System.out;
    private PrintStream erro = System.err;
    private int codigoDeSaida;

    RecuperacaoDeAdmin(UsuarioRepository repository, PasswordEncoder passwordEncoder, TrilhaDeUsuarios trilha) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.trilha = trilha;
    }

    /** Usado pelo main para subir a aplicação sem servidor web quando é só o comando. */
    public static boolean foiPedida(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.equals("--" + OPCAO) || arg.startsWith("--" + OPCAO + "="));
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPCAO)) {
            return;
        }
        codigoDeSaida = 0;
        List<String> valores = args.getOptionValues(OPCAO);
        if (valores.isEmpty() || valores.getFirst().isBlank()) {
            falhar("Informe o e-mail: --" + OPCAO + "=admin@academia.local");
            return;
        }
        String email = Usuario.normalizarEmail(valores.getFirst());
        Usuario usuario = repository.findByEmail(email).orElse(null);
        if (usuario == null) {
            falhar("Nenhum login com o e-mail " + email);
            return;
        }
        if (usuario.getPerfil() != Perfil.ADMIN) {
            falhar(email + " não é ADMIN. Para os demais, use a API (POST /api/usuarios/{id}/nova-senha "
                    + "ou /api/alunos/{id}/login/nova-senha)");
            return;
        }

        boolean estavaInativo = !usuario.isAtivo();
        if (estavaInativo) {
            Map<String, Object> antes = TrilhaDeUsuarios.retrato(usuario);
            usuario.reativar();
            trilha.alterado(usuario, "REATIVADO", antes);
        }
        String senhaTemporaria = GeradorDeSenhaTemporaria.gerar();
        // Também derruba todas as sessões abertas desse ADMIN.
        usuario.redefinirComSenhaTemporaria(passwordEncoder.encode(senhaTemporaria));
        trilha.ato(usuario, "SENHA_REDEFINIDA");  // pelo sistema (linha de comando)

        // Registro de auditoria, sem a senha.
        log.warn("Acesso do ADMIN {} recuperado pelo comando no servidor{}", email,
                estavaInativo ? " (o login estava inativo e foi reativado)" : "");
        saida.println();
        saida.println("Senha temporária do ADMIN " + email + ": " + senhaTemporaria);
        saida.println("Ela aparece só aqui. No primeiro acesso, o sistema vai exigir uma senha nova.");
        if (estavaInativo) {
            saida.println("O login estava inativo e foi reativado.");
        }
        saida.println();
    }

    private void falhar(String mensagem) {
        erro.println("Recuperação de ADMIN não realizada: " + mensagem);
        codigoDeSaida = 1;
    }

    @Override
    public int getExitCode() {
        return codigoDeSaida;
    }

    // Para os testes capturarem o que seria impresso no terminal.
    void redirecionarSaida(PrintStream saida, PrintStream erro) {
        this.saida = saida;
        this.erro = erro;
    }
}
