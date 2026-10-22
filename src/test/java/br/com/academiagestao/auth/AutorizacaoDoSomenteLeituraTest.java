package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A regra "só leitura passa só em consulta" está ligada à cadeia de segurança (ADR 0029).
 * Nenhuma rota de aluno altera dados hoje; para ver a regra agir, o token leva um perfil que
 * altera (ADMIN) junto com SOMENTE_LEITURA: a regra barra antes de o perfil ser conferido.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AutorizacaoDoSomenteLeituraTest {

    private static final RequestPostProcessor ADMIN_SO_LEITURA = jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority(SegurancaConfig.SOMENTE_LEITURA));

    @Autowired
    MockMvc mvc;

    @Test
    void consultaPassaEAlteracaoNao() throws Exception {
        mvc.perform(get("/api/planos").with(ADMIN_SO_LEITURA)).andExpect(status().isOk());
        mvc.perform(post("/api/planos").with(ADMIN_SO_LEITURA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"Plano\", \"valorMensal\": 100, \"duracaoMeses\": 1}"))
                .andExpect(status().isForbidden());
    }
}
