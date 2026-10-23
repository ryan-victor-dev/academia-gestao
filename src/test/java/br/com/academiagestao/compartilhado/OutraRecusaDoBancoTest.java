package br.com.academiagestao.compartilhado;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Só o índice único do nome vira "nome em uso" (409). Outra recusa do banco é um erro de
 * verdade, e não uma mensagem errada (revisão geral; etapa 7, B3). A outra recusa é simulada
 * com uma trava temporária (NOT VALID: não confere as linhas que já existem), criada e
 * desfeita pelo próprio teste. No servidor, o erro vira 500 em problem+json (PaginaDeErro).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OutraRecusaDoBancoTest {

    private static final RequestPostProcessor ADMIN = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void planoRecusadoPorOutraTravaNaoEhNomeEmUso() {
        String nome = "Recusado " + UUID.randomUUID();
        recusarNome("planos", nome, () -> mvc.perform(post("/api/planos").with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"%s\", \"valorMensal\": 100, \"duracaoMeses\": 1}".formatted(nome))));
    }

    @Test
    void exercicioRecusadoPorOutraTravaNaoEhNomeEmUso() {
        String nome = "Recusado " + UUID.randomUUID();
        recusarNome("exercicios", nome, () -> mvc.perform(post("/api/exercicios").with(ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\": \"%s\", \"grupoMuscular\": \"PEITO\"}".formatted(nome))));
    }

    private interface Chamada {
        void fazer() throws Exception;
    }

    private void recusarNome(String tabela, String nome, Chamada chamada) {
        jdbc.execute("ALTER TABLE " + tabela + " ADD CONSTRAINT ck_teste_outra_recusa CHECK (nome <> '" + nome
                + "') NOT VALID");
        try {
            // Sem um tratador, a exceção sobe até o servlet (no MockMvc, como exceção; no servidor, 500).
            assertThatThrownBy(chamada::fazer).rootCause().hasMessageContaining("ck_teste_outra_recusa");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tabela + " WHERE nome = ?", Integer.class, nome))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE " + tabela + " DROP CONSTRAINT ck_teste_outra_recusa");
        }
    }
}
