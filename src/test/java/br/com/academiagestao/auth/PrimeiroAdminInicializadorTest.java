package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "academia.admin-inicial.nome=Dona da Academia",
        "academia.admin-inicial.email=Dona@Academia.com",
        "academia.admin-inicial.senha=senha-forte-de-teste"
})
class PrimeiroAdminInicializadorTest {

    @Autowired
    PrimeiroAdminInicializador inicializador;

    @Autowired
    UsuarioRepository repository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void criaOAdminNaInicializacaoComSenhaEmBcrypt() {
        // O runner já rodou quando o contexto do Spring subiu.
        Usuario admin = repository.findByEmail("dona@academia.com").orElseThrow();

        assertThat(admin.getPerfil()).isEqualTo(Perfil.ADMIN);
        assertThat(admin.getSenhaHash())
                .isNotEqualTo("senha-forte-de-teste")
                .startsWith("$2a$");
        assertThat(passwordEncoder.matches("senha-forte-de-teste", admin.getSenhaHash())).isTrue();
    }

    @Test
    void naoCriaOutroAdminSeJaExisteUm() throws Exception {
        long antes = repository.count();

        inicializador.run(new DefaultApplicationArguments());

        assertThat(repository.count()).isEqualTo(antes);
    }
}
