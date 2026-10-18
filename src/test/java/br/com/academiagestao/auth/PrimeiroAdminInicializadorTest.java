package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void criaOAdminNaInicializacaoComSenhaEmBcrypt() {
        // O runner já rodou quando o contexto do Spring subiu.
        Usuario admin = repository.findByEmail("dona@academia.com").orElseThrow();

        assertThat(admin.getPerfil()).isEqualTo(Perfil.ADMIN);
        assertThat(admin.getSenhaHash())
                .isNotEqualTo("senha-forte-de-teste")
                .startsWith("$2a$");
        assertThat(passwordEncoder.matches("senha-forte-de-teste", admin.getSenhaHash())).isTrue();
        // A senha do .env é só de primeiro acesso.
        assertThat(admin.isPrecisaTrocarSenha()).isTrue();
        // Na trilha, criado pelo sistema (autor vazio), sem senha.
        var registro = jdbc.queryForMap("SELECT autor, acao, alteracoes::text AS alteracoes FROM auditoria "
                + "WHERE entidade_id = ?", admin.getId());
        assertThat(registro).containsEntry("acao", "CRIADO").containsEntry("autor", null);
        assertThat((String) registro.get("alteracoes")).contains("dona@academia.com").doesNotContain("$2a$");
    }

    @Test
    void naoCriaOutroAdminSeJaExisteUm() throws Exception {
        long antes = repository.count();

        inicializador.run(new DefaultApplicationArguments());

        assertThat(repository.count()).isEqualTo(antes);
    }
}
