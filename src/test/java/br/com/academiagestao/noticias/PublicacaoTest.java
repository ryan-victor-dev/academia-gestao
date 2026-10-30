package br.com.academiagestao.noticias;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notícias, comunicados e eventos (ADR 0047, decisão 6): o ADMIN e a recepção publicam, editam e excluem;
 * todos que têm login leem.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PublicacaoTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    private final List<UUID> usuarios = new ArrayList<>();
    private UUID recepcao;
    private UUID admin;

    @BeforeEach
    void cadastrar() {
        // As publicações de outros testes (ou de antes) não entram na conta das listas.
        jdbc.update("DELETE FROM publicacoes");
        recepcao = daEquipe("RECEPCAO");
        admin = daEquipe("ADMIN");
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'PUBLICACAO'");
        jdbc.update("DELETE FROM publicacoes");
        for (UUID usuario : usuarios) {
            limpeza.apagar("usuarios", usuario);
        }
    }

    private UUID daEquipe(String perfil) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, ?, ?, 'x', ?)",
                id, "Pessoa " + perfil, "publicacao-" + id + "@teste.local", perfil);
        usuarios.add(id);
        return id;
    }

    private static RequestPostProcessor como(String perfil, UUID usuario) {
        return jwt().jwt(j -> j.subject(usuario.toString()).claim("perfil", perfil))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private ResultActions publicar(RequestPostProcessor quem, String corpo) throws Exception {
        return mvc.perform(post("/api/publicacoes").with(quem).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private String publicada(String corpo) throws Exception {
        return JsonPath.read(publicar(como("RECEPCAO", recepcao), corpo).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String noticia(String titulo) {
        return "{\"tipo\": \"NOTICIA\", \"titulo\": \"" + titulo + "\", \"texto\": \"Texto de " + titulo + "\"}";
    }

    private static String evento(String titulo, Instant inicio, Instant fim) {
        return "{\"tipo\": \"EVENTO\", \"titulo\": \"%s\", \"texto\": \"Venha!\", \"eventoInicio\": \"%s\"%s, \"eventoLocal\": \"Sala 2\"}"
                .formatted(titulo, inicio, fim == null ? "" : ", \"eventoFim\": \"" + fim + "\"");
    }

    @Test
    void aRecepcaoPublicaComLinksETodosLeem() throws Exception {
        publicar(como("RECEPCAO", recepcao), """
                {"tipo": "COMUNICADO", "titulo": " Feriado ", "texto": "Fechados na sexta.<script>x</script>",
                 "links": [{"url": "https://academia.exemplo/horarios", "rotulo": "Horários"}, {"url": "http://b.exemplo"}]}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/api/publicacoes/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.titulo").value("Feriado"))
                .andExpect(jsonPath("$.autorNome").value("Pessoa RECEPCAO"))
                .andExpect(jsonPath("$.editadaEm").doesNotExist());

        for (String perfil : List.of("ALUNO", "INSTRUTOR", "RECEPCAO", "ADMIN")) {
            mvc.perform(get("/api/publicacoes").with(como(perfil, UUID.randomUUID()))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElementos").value(1))
                    // Texto simples: guardado como veio, e a tela o mostra como texto (nunca como HTML).
                    .andExpect(jsonPath("$.conteudo[0].texto").value("Fechados na sexta.<script>x</script>"))
                    .andExpect(jsonPath("$.conteudo[0].links[*].url", contains("https://academia.exemplo/horarios", "http://b.exemplo")))
                    .andExpect(jsonPath("$.conteudo[0].links[0].rotulo").value("Horários"));
        }
        mvc.perform(get("/api/publicacoes")).andExpect(status().isUnauthorized());
    }

    @Test
    void daMaisNovaParaAMaisAntigaPaginadaEComFiltroPeloTipo() throws Exception {
        String primeira = publicada(noticia("Primeira"));
        jdbc.update("UPDATE publicacoes SET criada_em = criada_em - interval '1 hour' WHERE id = ?::uuid", primeira);
        publicada(noticia("Segunda"));
        publicada(evento("Aulão", Instant.now().plus(Duration.ofDays(3)), null));

        mvc.perform(get("/api/publicacoes?tamanho=2").with(como("ALUNO", UUID.randomUUID())))
                .andExpect(jsonPath("$.totalElementos").value(3)).andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.conteudo[*].titulo", contains("Aulão", "Segunda")));
        mvc.perform(get("/api/publicacoes?tamanho=2&pagina=1").with(como("ALUNO", UUID.randomUUID())))
                .andExpect(jsonPath("$.conteudo[*].titulo", contains("Primeira")));
        mvc.perform(get("/api/publicacoes?tipo=EVENTO").with(como("ALUNO", UUID.randomUUID())))
                .andExpect(jsonPath("$.conteudo[*].titulo", contains("Aulão")))
                .andExpect(jsonPath("$.conteudo[0].eventoLocal").value("Sala 2"));
        mvc.perform(get("/api/publicacoes?tamanho=51").with(como("ALUNO", UUID.randomUUID()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/publicacoes?tipo=OUTRO").with(como("ALUNO", UUID.randomUUID()))).andExpect(status().isBadRequest());
    }

    @Test
    void proximosEventosSoOsQueNaoTerminaramPeloInicio() throws Exception {
        Instant agora = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        publicada(evento("Terminou ontem", agora.minus(Duration.ofDays(2)), agora.minus(Duration.ofDays(1))));
        publicada(evento("Sem fim, começou há 2 dias", agora.minus(Duration.ofDays(2)), null));
        publicada(evento("Sem fim, começou há 2 horas", agora.minus(Duration.ofHours(2)), null));
        publicada(evento("Acontecendo", agora.minus(Duration.ofHours(1)), agora.plus(Duration.ofHours(1))));
        publicada(evento("Semana que vem", agora.plus(Duration.ofDays(7)), null));
        publicada(evento("Amanhã", agora.plus(Duration.ofDays(1)), null));
        publicada(noticia("Notícia não é evento"));

        mvc.perform(get("/api/publicacoes/proximos-eventos").with(como("ALUNO", UUID.randomUUID())))
                .andExpect(jsonPath("$[*].titulo", contains("Sem fim, começou há 2 horas", "Acontecendo", "Amanhã", "Semana que vem")));
    }

    @Test
    void editarTrocaOsLinksEMarcaQuemEditou() throws Exception {
        String id = publicada("""
                {"tipo": "NOTICIA", "titulo": "Antes", "texto": "T", "links": [{"url": "https://a.exemplo"}, {"url": "https://b.exemplo"}]}""");

        mvc.perform(put("/api/publicacoes/" + id).with(como("ADMIN", admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\": \"COMUNICADO\", \"titulo\": \"Depois\", \"texto\": \"T2\", \"links\": [{\"url\": \"https://c.exemplo\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("COMUNICADO"))
                .andExpect(jsonPath("$.titulo").value("Depois"))
                .andExpect(jsonPath("$.links[*].url", contains("https://c.exemplo")))
                .andExpect(jsonPath("$.autorNome").value("Pessoa RECEPCAO"))
                .andExpect(jsonPath("$.editadaEm").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT editada_por FROM publicacoes WHERE id = ?::uuid", UUID.class, id)).isEqualTo(admin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publicacao_links WHERE publicacao_id = ?::uuid", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void excluirLevaOsLinksEDepoisDa404() throws Exception {
        String id = publicada("{\"tipo\": \"NOTICIA\", \"titulo\": \"Sai\", \"texto\": \"T\", \"links\": [{\"url\": \"https://a.exemplo\"}]}");

        mvc.perform(delete("/api/publicacoes/" + id).with(como("RECEPCAO", recepcao))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publicacao_links WHERE publicacao_id = ?::uuid", Integer.class, id)).isZero();
        mvc.perform(get("/api/publicacoes/" + id).with(como("ALUNO", UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/publicacoes/" + id).with(como("ADMIN", admin))).andExpect(status().isNotFound());
        mvc.perform(put("/api/publicacoes/" + id).with(como("ADMIN", admin)).contentType(MediaType.APPLICATION_JSON)
                .content(noticia("X"))).andExpect(status().isNotFound());
    }

    @Test
    void aTrilhaRegistraPublicarEditarEExcluir() throws Exception {
        String id = publicada(noticia("Na trilha"));
        mvc.perform(put("/api/publicacoes/" + id).with(como("ADMIN", admin)).contentType(MediaType.APPLICATION_JSON)
                .content(noticia("Editada"))).andExpect(status().isOk());
        mvc.perform(delete("/api/publicacoes/" + id).with(como("ADMIN", admin))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("SELECT acao FROM auditoria WHERE entidade_id = ?::uuid ORDER BY momento", String.class, id))
                .containsExactly("PUBLICADA", "EDITADA", "EXCLUIDA");
        assertThat(jdbc.queryForObject("SELECT alteracoes -> 'titulo' ->> 'antes' FROM auditoria WHERE entidade_id = ?::uuid "
                + "AND acao = 'EDITADA'", String.class, id)).isEqualTo("Na trilha");
        assertThat(jdbc.queryForObject("SELECT autor FROM auditoria WHERE entidade_id = ?::uuid AND acao = 'PUBLICADA'",
                String.class, id)).isEqualTo(recepcao.toString());
    }

    @ParameterizedTest(name = "{0} não publica, não edita, não exclui")
    @CsvSource({"INSTRUTOR", "ALUNO"})
    void soOAdminEARecepcaoMexem(String perfil) throws Exception {
        String id = publicada(noticia("Fica"));
        RequestPostProcessor quem = como(perfil, UUID.randomUUID());

        publicar(quem, noticia("Não")).andExpect(status().isForbidden());
        mvc.perform(put("/api/publicacoes/" + id).with(quem).contentType(MediaType.APPLICATION_JSON).content(noticia("Não")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/publicacoes/" + id).with(quem)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT titulo FROM publicacoes WHERE id = ?::uuid", String.class, id)).isEqualTo("Fica");
    }

    @Test
    void dadosInvalidosDao400ComAMensagem() throws Exception {
        RequestPostProcessor quem = como("RECEPCAO", recepcao);
        publicar(quem, "{\"tipo\": \"EVENTO\", \"titulo\": \"T\", \"texto\": \"X\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("eventoInicio: o evento precisa de data e hora"));
        publicar(quem, "{\"tipo\": \"NOTICIA\", \"titulo\": \"T\", \"texto\": \"X\", \"links\": [{\"url\": \"javascript:alert(1)\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail", org.hamcrest.Matchers.startsWith("links: endereço inválido")));
        publicar(quem, "{\"tipo\": \"NOTICIA\", \"titulo\": \"T\", \"texto\": \"X\", \"links\": [null]}").andExpect(status().isBadRequest());
        publicar(quem, "{\"tipo\": \"NOTICIA\", \"titulo\": \"T\"}").andExpect(status().isBadRequest());
        publicar(quem, "{\"tipo\": \"BOATO\", \"titulo\": \"T\", \"texto\": \"X\"}").andExpect(status().isBadRequest());
        publicar(quem, "{\"tipo\": \"NOTICIA\", \"titulo\": \"T\", \"texto\": \"X\u0007\"}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publicacoes", Integer.class)).isZero();
    }

    @Test
    void oBancoRecusaLinkForaDaWebEEventoSemData() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO publicacoes (id, tipo, titulo, texto, criada_em, criada_por) VALUES (?::uuid, 'NOTICIA', 'T', 'X', now(), ?)",
                id, recepcao);
        assertThat(tentar("INSERT INTO publicacao_links (publicacao_id, ordem, url) VALUES ('" + id + "', 0, 'javascript:alert(1)')"))
                .contains("publicacao_links_url_check");
        assertThat(tentar("INSERT INTO publicacao_links (publicacao_id, ordem, url) VALUES ('" + id + "', 0, 'HTTPS://a.exemplo')"))
                .contains("publicacao_links_url_check");
        assertThat(tentar("UPDATE publicacoes SET tipo = 'EVENTO' WHERE id = '" + id + "'")).contains("ck_publicacoes_evento");
        assertThat(tentar("UPDATE publicacoes SET evento_local = 'Sala' WHERE id = '" + id + "'"))
                .contains("ck_publicacoes_so_evento_tem_fim_e_local");
    }

    private String tentar(String sql) {
        try {
            jdbc.update(sql);
            return "aceitou";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
