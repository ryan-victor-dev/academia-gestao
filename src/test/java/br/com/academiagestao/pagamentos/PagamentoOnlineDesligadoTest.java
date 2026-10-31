package br.com.academiagestao.pagamentos;

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

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sem empresa configurada (o padrão), o app não oferece pagar, e nada responde como se pagasse (ADR 0034). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PagamentoOnlineDesligadoTest {

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor aluno() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("alunoId", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ALUNO"));
    }

    @Test
    void desligadoPorPadrao() throws Exception {
        mvc.perform(get("/api/pagamentos-online/me").with(aluno()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ligado").value(false))
                .andExpect(jsonPath("$.simulado").value(false))
                .andExpect(jsonPath("$.pagaveis").isEmpty());
        mvc.perform(post("/api/pagamentos-online/me").with(aluno()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cobrancaId\": \"" + UUID.randomUUID() + "\", \"forma\": \"PIX\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("O pagamento online está desligado"));
        mvc.perform(post("/api/pagamentos-online/aviso").header("X-Aviso-Token", "qualquer").content("sim_1"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/pagamentos-online/me/" + UUID.randomUUID() + "/simular-pagamento").with(aluno()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/pagamentos-online/configuracao").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(jsonPath("$.provedor").value("desligado"))
                .andExpect(jsonPath("$.simulado").value(false));
    }

    /** O boleto do balcão (ADR 0047) também não existe sem empresa: a tela nem oferece, e a API recusa. */
    @Test
    void desligadoOBalcaoNaoGeraBoleto() throws Exception {
        RequestPostProcessor recepcao = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));
        mvc.perform(get("/api/pagamentos-online/balcao").with(recepcao))
                .andExpect(status().isOk()).andExpect(jsonPath("$.boleto").value(false));
        mvc.perform(post("/api/pagamentos-online/balcao/boleto").with(recepcao).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alunoId\": \"" + UUID.randomUUID() + "\", \"cobrancaId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("O pagamento online está desligado"));
    }

    /** A tela do boleto simulado só existe com o provedor simulado. */
    @Test
    void desligadoNaoHaBoletoSimulado() throws Exception {
        RequestPostProcessor recepcao = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));
        mvc.perform(get("/api/pagamentos-online/" + UUID.randomUUID() + "/boleto-simulado").with(recepcao))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Simulação desligada"));
    }
}
