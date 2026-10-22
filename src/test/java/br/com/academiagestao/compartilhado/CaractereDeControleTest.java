package br.com.academiagestao.compartilhado;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Texto com caractere de controle é recusado na entrada, em qualquer módulo (diagnóstico da
 * etapa 7, T4): antes, o byte nulo chegava ao banco e virava 500.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CaractereDeControleTest {

    private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    private static final String MENSAGEM = ": tem um caractere de controle (só quebra de linha e tab são aceitos)";

    @Autowired
    MockMvc mvc;

    @Autowired
    LimpezaDoBanco limpeza;

    private final JsonMapper json = JsonMapper.builder().build();

    private String corpo(Object valor) {
        return json.writeValueAsString(valor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "\u0001", "\u001f", "\u001b"})
    void noJsonDa400ComOCampo(String controle) throws Exception {
        mvc.perform(post("/api/planos").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("nome", "Plano" + controle + "x", "valorMensal", 100, "duracaoMeses", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.detail").value("nome" + MENSAGEM));
    }

    // O trim() do Java tirava o nulo das pontas de alguns campos; o do meio dava 500.
    @ParameterizedTest
    @ValueSource(strings = {"Plano\u0000", "\u0000Plano"})
    void nasPontasDoTextoTambem(String nome) throws Exception {
        mvc.perform(post("/api/planos").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("nome", nome, "valorMensal", 100, "duracaoMeses", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("nome" + MENSAGEM));
    }

    @Test
    void campoDentroDeListasTemOCaminhoInteiro() throws Exception {
        var item = Map.of("exercicioId", UUID.randomUUID(), "series", 3, "repeticoes", "10");
        var comNulo = Map.of("exercicioId", UUID.randomUUID(), "series", 3, "repeticoes", "10", "carga", "20\u0000kg");
        mvc.perform(post("/api/fichas").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("alunoId", UUID.randomUUID(), "divisoes",
                                List.of(Map.of("nome", "A", "exercicios", List.of(item)),
                                        Map.of("nome", "B", "exercicios", List.of(item, comNulo)))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("divisoes[1].exercicios[1].carga" + MENSAGEM));
    }

    @Test
    void noLoginTambem() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("email", "a\u0000@academia.com", "senha", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("email" + MENSAGEM));
    }

    @Test
    void noParametroDaUrl() throws Exception {
        mvc.perform(get("/api/alunos").with(ADMIN).param("busca", "Ana\u0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.detail").value("busca" + MENSAGEM));
        // O texto sem controle chega inteiro à busca.
        UUID aluno = UUID.randomUUID();
        String nome = "Busca " + aluno;
        String resposta = mvc.perform(post("/api/alunos").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("nome", nome, "cpf", CpfsParaTeste.aleatorio()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        try {
            mvc.perform(get("/api/alunos").with(ADMIN).param("busca", nome))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElementos").value(1))
                    .andExpect(jsonPath("$.conteudo[0].nome").value(nome));
            mvc.perform(get("/api/alunos").with(ADMIN).param("busca", "Ninguém " + UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElementos").value(0));
        } finally {
            limpeza.apagar("alunos", UUID.fromString(json.readTree(resposta).get("id").asString()));
        }
    }

    @Test
    void quebraDeLinhaTabEEspacoSaoAceitos() throws Exception {
        String resposta = mvc.perform(post("/api/alunos").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("nome", "Ana Maria", "cpf", CpfsParaTeste.aleatorio(),
                                "observacoes", "Linha 1\r\nLinha 2\ttab"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observacoes").value("Linha 1\r\nLinha 2\ttab"))
                .andReturn().getResponse().getContentAsString();
        limpeza.apagar("alunos", UUID.fromString(json.readTree(resposta).get("id").asString()));
    }
}
