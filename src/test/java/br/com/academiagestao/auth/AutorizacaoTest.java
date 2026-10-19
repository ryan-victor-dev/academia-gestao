package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regras de autorização por perfil. Aqui o token é simulado pelo spring-security-test
 * (jwt()), sem passar pelo login; o fluxo com token real fica em outro teste.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AutorizacaoTest {

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository repository;

    private Usuario recepcao;

    @BeforeEach
    void cadastrar() {
        limpeza.apagarTudo();
        recepcao = repository.save(Usuario.daEquipe("Ana", "ana@academia.com", "hash", Perfil.RECEPCAO));
    }

    private static RequestPostProcessor comPerfil(Usuario usuario) {
        return jwt().jwt(j -> j.subject(usuario.getId().toString()).claim("perfil", usuario.getPerfil().name()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + usuario.getPerfil().name()));
    }

    @Test
    void meDevolveOsDadosDoDonoDoTokenSemOHash() throws Exception {
        mvc.perform(get("/api/me").with(comPerfil(recepcao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recepcao.getId().toString()))
                .andExpect(jsonPath("$.email").value("ana@academia.com"))
                .andExpect(jsonPath("$.perfil").value("RECEPCAO"))
                .andExpect(jsonPath("$.senhaHash").doesNotExist());
    }

    @Test
    void meDeUsuarioDesativadoDepoisDoLoginDevolve401() throws Exception {
        recepcao.desativar();
        repository.save(recepcao);

        mvc.perform(get("/api/me").with(comPerfil(recepcao)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void adminListaUsuarios() throws Exception {
        Usuario admin = repository.save(Usuario.daEquipe("Bia", "bia@academia.com", "hash", Perfil.ADMIN));

        mvc.perform(get("/api/usuarios").with(comPerfil(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void perfilSemPermissaoRecebe403EmProblemDetail() throws Exception {
        mvc.perform(get("/api/usuarios").with(comPerfil(recepcao)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Acesso negado"))
                .andExpect(jsonPath("$.instance").value("/api/usuarios"));
    }

    @Test
    void semTokenRecebe401EmProblemDetailComWwwAuthenticate() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Não autenticado"));
    }

    @Test
    void tokenInvalidoRecebe401EmProblemDetail() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer isto.nao.eh-um-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\"")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }
}
