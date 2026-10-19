package br.com.academiagestao.alunos;

import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.compartilhado.Calendario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cadastro e edição de aluno tiram o "hoje" do relógio único, no fuso da academia
 * (ADR 0023): a validação do pedido ({@code @Past}) e a da entidade.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RelogioNosAlunosTest {

    private static final RequestPostProcessor RECEPCAO =
            jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("perfil", "RECEPCAO"))
                    .authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RelogioDeTeste relogio;

    private final List<String> criados = new ArrayList<>();

    @AfterEach
    void limpar() {
        relogio.restaurar();
        for (String id : criados) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?::uuid", id);
            jdbc.update("DELETE FROM alunos WHERE id = ?::uuid", id);
        }
    }

    private static String corpo(String cpf, LocalDate nascimento) {
        return """
                {"nome": "Ana", "cpf": "%s", "dataNascimento": "%s"}""".formatted(cpf, nascimento);
    }

    private ResultActions cadastrar(String cpf, LocalDate nascimento) throws Exception {
        return mvc.perform(post("/api/alunos").with(RECEPCAO)
                .contentType(MediaType.APPLICATION_JSON).content(corpo(cpf, nascimento)));
    }

    private LocalDate hojeNaAcademia() {
        return Calendario.hoje(relogio.instant());
    }

    /** Com o relógio 3 dias à frente, nascer "amanhã" (pela data real) já é passado. */
    @Test
    void cadastroUsaORelogio() throws Exception {
        relogio.parar();
        relogio.adiantar(Duration.ofDays(3));
        LocalDate ontem = hojeNaAcademia().minusDays(1);

        String resposta = cadastrar(CpfsParaTeste.aleatorio(), ontem)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        criados.add(JsonPath.read(resposta, "$.id"));

        assertThat(Instant.parse(JsonPath.read(resposta, "$.criadoEm")))
                .isEqualTo(relogio.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** Hoje (no relógio) ainda não é passado: o {@code @Past} usa o mesmo relógio. */
    @Test
    void hojeNaoEhPassado() throws Exception {
        relogio.parar();
        relogio.adiantar(Duration.ofDays(3));

        cadastrar(CpfsParaTeste.aleatorio(), hojeNaAcademia()).andExpect(status().isBadRequest());
    }

    /**
     * Às 23h30 em São Paulo, em UTC já é amanhã: o {@code @Past} no fuso do servidor aceitaria
     * nascer "hoje" à noite e recusaria de manhã. No da academia, recusa sempre.
     */
    @Test
    void hojeNaoEhPassadoNemANoite() throws Exception {
        LocalDate amanha = Calendario.hoje(Instant.now()).plusDays(1);
        relogio.fixar(amanha.atTime(23, 30).atZone(Calendario.FUSO).toInstant());

        cadastrar(CpfsParaTeste.aleatorio(), amanha).andExpect(status().isBadRequest());
    }

    /** Na edição, a entidade também confere com o relógio. */
    @Test
    void edicaoUsaORelogio() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String resposta = cadastrar(cpf, LocalDate.of(1990, 1, 1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(resposta, "$.id");
        criados.add(id);

        relogio.parar();
        relogio.adiantar(Duration.ofDays(3));

        mvc.perform(put("/api/alunos/" + id).with(RECEPCAO).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo(cpf, hojeNaAcademia().minusDays(1))))
                .andExpect(status().isOk());
    }
}
