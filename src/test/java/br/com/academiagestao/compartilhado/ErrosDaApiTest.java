package br.com.academiagestao.compartilhado;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Erros gerados pelo próprio Spring MVC saem em português, no mesmo formato dos nossos. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ErrosDaApiTest {

    @Autowired
    MockMvc mvc;

    private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @Test
    void jsonMalformado() throws Exception {
        mvc.perform(post("/api/alunos").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"nome\": "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Corpo da requisição inválido"))
                .andExpect(jsonPath("$.detail").value(
                        "O JSON está malformado ou tem um valor no formato errado (datas no formato AAAA-MM-DD)."));
    }

    @Test
    void dataEmFormatoErrado() throws Exception {
        mvc.perform(post("/api/alunos").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"Ana\", \"cpf\": \"52998224725\", \"dataNascimento\": \"10/03/1995\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição inválido"));
    }

    @Test
    void idQueNaoEhUuidNaUrl() throws Exception {
        mvc.perform(get("/api/alunos/abc").with(ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Parâmetro inválido"))
                .andExpect(jsonPath("$.detail").value("O valor 'abc' não é válido para 'id'."));
    }

    @Test
    void metodoNaoPermitido() throws Exception {
        mvc.perform(delete("/api/alunos").with(ADMIN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("Método não permitido"))
                .andExpect(jsonPath("$.detail").value("O método DELETE não é aceito nesta rota."));
    }

    @Test
    void corpoQueNaoEhJson() throws Exception {
        mvc.perform(post("/api/alunos").with(ADMIN).contentType(MediaType.TEXT_PLAIN).content("Ana"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Formato não suportado"))
                .andExpect(jsonPath("$.detail").value("Envie o corpo como application/json."));
    }

    @Test
    void rotaInexistente() throws Exception {
        mvc.perform(get("/api/nao-existe").with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Rota não encontrada"))
                .andExpect(jsonPath("$.detail").value("Esta rota não existe."));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"nome\": \"P\", \"valorMensal\": 100, \"duracaoMeses\": 1.5}",     // decimal num inteiro
            "{\"nome\": \"P\", \"valorMensal\": 100, \"duracaoMeses\": \"12\"}",  // texto num inteiro
            "{\"nome\": \"P\", \"valorMensal\": \"129.90\", \"duracaoMeses\": 1}", // texto num valor
            "{\"nome\": \"P\", \"valorMensal\": 100, \"duracaoMeses\": 1} lixo",    // lixo depois do JSON
    })
    void valorNoTipoErradoEhRecusadoEmVezDeConvertidoSemAviso(String corpo) throws Exception {
        mvc.perform(post("/api/planos").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição inválido"));
    }
}
