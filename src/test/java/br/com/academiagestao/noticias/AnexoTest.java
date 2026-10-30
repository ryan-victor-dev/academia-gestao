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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anexos das publicações (ADR 0047, decisão 6): a recepção e o ADMIN enviam fotos, vídeos e PDFs, guardados no
 * banco em pedaços de 1 MB; quem tem login abre pelo endereço assinado.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AnexoTest {

    static final int MB = 1024 * 1024;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    AnexoService service;

    private final List<UUID> usuarios = new ArrayList<>();
    private UUID recepcao;
    private UUID publicacao;

    @BeforeEach
    void cadastrar() {
        recepcao = daEquipe("RECEPCAO");
        publicacao = UUID.randomUUID();
        jdbc.update("INSERT INTO publicacoes (id, tipo, titulo, texto, criada_em, criada_por) VALUES (?, 'NOTICIA', 'Com anexos', 'T', now(), ?)",
                publicacao, recepcao);
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'PUBLICACAO' AND entidade_id = ?", publicacao);
        jdbc.update("DELETE FROM publicacoes WHERE criada_por = ANY (?)", (Object) usuarios.toArray(UUID[]::new));
        for (UUID usuario : usuarios) {
            limpeza.apagar("usuarios", usuario);
        }
    }

    private UUID daEquipe(String perfil) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, ?, ?, 'x', ?)",
                id, "Pessoa " + perfil, "anexo-" + id + "@teste.local", perfil);
        usuarios.add(id);
        return id;
    }

    private static RequestPostProcessor como(String perfil, UUID usuario) {
        return jwt().jwt(j -> j.subject(usuario.toString()).claim("perfil", perfil))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    /** Um arquivo do tamanho pedido, começando pela assinatura do tipo (o resto, bytes quaisquer). */
    static byte[] arquivo(String inicioEmHex, int tamanho) {
        byte[] inicio = HexFormat.of().parseHex(inicioEmHex);
        byte[] dados = new byte[tamanho];
        ThreadLocalRandom.current().nextBytes(dados);
        System.arraycopy(inicio, 0, dados, 0, inicio.length);
        return dados;
    }

    static byte[] jpeg(int tamanho) {
        return arquivo("ffd8ffe000104a464946", tamanho);
    }

    static byte[] mp4(int tamanho) {
        return arquivo("000000206674797069736f6d", tamanho);
    }

    static byte[] pdf(int tamanho) {
        return arquivo("255044462d312e370a", tamanho);
    }

    private ResultActions enviar(RequestPostProcessor quem, UUID publicacaoId, String nome, byte[] conteudo) throws Exception {
        return mvc.perform(post("/api/publicacoes/" + publicacaoId + "/anexos").with(quem)
                .header("X-Nome-Do-Arquivo", URLEncoder.encode(nome, StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(conteudo));
    }

    private String enviado(String nome, byte[] conteudo) throws Exception {
        return JsonPath.read(enviar(como("RECEPCAO", recepcao), publicacao, nome, conteudo).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.endereco");
    }

    private int anexosNoBanco() {
        return jdbc.queryForObject("SELECT count(*) FROM publicacao_anexos WHERE publicacao_id = ?", Integer.class, publicacao);
    }

    @Test
    void fotoEnviadaAbrePeloEnderecoAssinadoSemLogin() throws Exception {
        byte[] foto = jpeg(300_000);
        String corpo = enviar(como("RECEPCAO", recepcao), publicacao, "Turma de sábado ção.jpg", foto)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("IMAGEM"))
                .andExpect(jsonPath("$.conteudoTipo").value("image/jpeg"))
                .andExpect(jsonPath("$.nome").value("Turma de sábado ção.jpg"))
                .andExpect(jsonPath("$.tamanho").value(300_000))
                .andExpect(jsonPath("$.endereco", startsWith("/api/anexos/")))
                .andReturn().getResponse().getContentAsString();
        String endereco = JsonPath.read(corpo, "$.endereco");

        MvcResult baixado = mvc.perform(get(endereco)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''Turma%20de%20s%C3%A1bado")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andReturn();
        assertThat(baixado.getResponse().getContentAsByteArray()).isEqualTo(foto);

        // A publicação traz o anexo, com o mesmo endereço, para qualquer perfil.
        mvc.perform(get("/api/publicacoes/" + publicacao).with(como("ALUNO", UUID.randomUUID())))
                .andExpect(jsonPath("$.anexos[0].nome").value("Turma de sábado ção.jpg"))
                .andExpect(jsonPath("$.anexos[0].endereco").value(endereco));
    }

    @Test
    void videoDeVariosPedacosVemInteiroEPorPartes() throws Exception {
        byte[] video = mp4(2 * MB + 12_345);
        String endereco = enviado("aula.mp4", video);
        assertThat(jdbc.queryForList("SELECT octet_length(dados) FROM anexo_partes p JOIN publicacao_anexos a ON a.id = p.anexo_id "
                + "WHERE a.publicacao_id = ? ORDER BY ordem", Integer.class, publicacao)).containsExactly(MB, MB, 12_345);

        assertThat(mvc.perform(get(endereco)).andExpect(status().isOk()).andExpect(header().string("Accept-Ranges", "bytes"))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(video);
        // Um trecho que atravessa a divisa entre o primeiro e o segundo pedaço.
        MvcResult trecho = mvc.perform(get(endereco).header("Range", "bytes=" + (MB - 10) + "-" + (MB + 9)))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes " + (MB - 10) + "-" + (MB + 9) + "/" + video.length))
                .andReturn();
        assertThat(trecho.getResponse().getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(video, MB - 10, MB + 10));
        MvcResult fim = mvc.perform(get(endereco).header("Range", "bytes=-100")).andExpect(status().isPartialContent()).andReturn();
        assertThat(fim.getResponse().getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(video, video.length - 100, video.length));
    }

    @Test
    void pdfVemComoDownload() throws Exception {
        String endereco = enviado("regulamento.pdf", pdf(40_000));
        mvc.perform(get(endereco)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")));
    }

    @Test
    void oTipoVemDoConteudoENaoDoNome() throws Exception {
        RequestPostProcessor quem = como("RECEPCAO", recepcao);
        enviar(quem, publicacao, "foto.jpg", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnsupportedMediaType());
        enviar(quem, publicacao, "logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x</script></svg>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnsupportedMediaType());
        enviar(quem, publicacao, "vazio.jpg", new byte[0]).andExpect(status().isBadRequest());
        // Um PDF com nome de foto é um PDF.
        enviar(quem, publicacao, "foto.jpg", pdf(1000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteudoTipo").value("application/pdf"));
        assertThat(anexosNoBanco()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} acima do teto de {1} MB")
    @CsvSource({"jpeg, 5", "pdf, 10", "mp4, 50"})
    void grandeDemaisEhRecusadoESemSobrarNada(String tipo, int tetoEmMb) throws Exception {
        byte[] noTeto = switch (tipo) {
            case "jpeg" -> jpeg(tetoEmMb * MB);
            case "pdf" -> pdf(tetoEmMb * MB);
            default -> mp4(tetoEmMb * MB);
        };
        enviar(como("RECEPCAO", recepcao), publicacao, "no-teto", noTeto).andExpect(status().isCreated());
        if (tipo.equals("mp4")) {
            // Acima de 50 MB, nem o corpo passa (o limite do pedido).
            enviar(como("RECEPCAO", recepcao), publicacao, "grande", mp4(50 * MB + 1)).andExpect(status().isContentTooLarge());
        } else {
            byte[] acima = Arrays.copyOf(noTeto, noTeto.length + 1);
            enviar(como("RECEPCAO", recepcao), publicacao, "grande", acima).andExpect(status().isContentTooLarge())
                    .andExpect(jsonPath("$.detail").value(containsString(tetoEmMb + " MB")));
        }
        assertThat(anexosNoBanco()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publicacao_anexos WHERE situacao = 'ENVIANDO'", Integer.class)).isZero();
    }

    @Test
    void enderecoComAssinaturaErradaVencidaOuDeOutroAnexoDa404() throws Exception {
        String endereco = enviado("a.jpg", jpeg(1000));
        String outro = enviado("b.jpg", jpeg(1000));
        String idDoOutro = outro.replaceAll("/api/anexos/([^?]+).*", "$1");

        mvc.perform(get(endereco.replaceAll("assinatura=.", "assinatura=X"))).andExpect(status().isNotFound());
        mvc.perform(get(endereco.replaceAll("/api/anexos/[^?]+", "/api/anexos/" + idDoOutro))).andExpect(status().isNotFound());
        long expira = Long.parseLong(endereco.replaceAll(".*expira=(\\d+).*", "$1"));
        mvc.perform(get(endereco.replace("expira=" + expira, "expira=" + (expira + 3600)))).andExpect(status().isNotFound());
        mvc.perform(get(endereco.replaceAll("&assinatura=.*", ""))).andExpect(status().isBadRequest());
        mvc.perform(get(endereco)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} não envia nem exclui")
    @CsvSource({"INSTRUTOR", "ALUNO"})
    void soOAdminEARecepcaoMexemNosAnexos(String perfil) throws Exception {
        String endereco = enviado("a.jpg", jpeg(1000));
        String anexo = endereco.replaceAll("/api/anexos/([^?]+).*", "$1");
        enviar(como(perfil, UUID.randomUUID()), publicacao, "b.jpg", jpeg(1000)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/publicacoes/" + publicacao + "/anexos/" + anexo).with(como(perfil, UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/publicacoes/" + publicacao + "/anexos").header("X-Nome-Do-Arquivo", "c.jpg")
                .content(jpeg(1000))).andExpect(status().isUnauthorized());
        assertThat(anexosNoBanco()).isEqualTo(1);
    }

    @Test
    void ateDezPorPublicacao() throws Exception {
        for (int i = 0; i < AnexoService.MAXIMO_POR_PUBLICACAO; i++) {
            enviado("foto" + i + ".jpg", jpeg(100));
        }
        enviar(como("RECEPCAO", recepcao), publicacao, "demais.jpg", jpeg(100)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cada publicação tem no máximo 10 anexos"));
        assertThat(anexosNoBanco()).isEqualTo(10);
    }

    @Test
    void publicacaoInexistenteENomeInvalido() throws Exception {
        enviar(como("ADMIN", recepcao), UUID.randomUUID(), "a.jpg", jpeg(100)).andExpect(status().isNotFound());
        enviar(como("ADMIN", recepcao), publicacao, "foto\r\nSet-Cookie: x.jpg", jpeg(100)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/publicacoes/" + publicacao + "/anexos").with(como("ADMIN", recepcao)).content(jpeg(100)))
                .andExpect(status().isBadRequest());
        assertThat(anexosNoBanco()).isZero();
    }

    @Test
    void excluirOAnexoEAPublicacaoLevamOsPedacosEATrilhaRegistra() throws Exception {
        String endereco = enviado("a.jpg", jpeg(1000));
        enviado("b.pdf", pdf(1000));
        String anexo = endereco.replaceAll("/api/anexos/([^?]+).*", "$1");

        mvc.perform(delete("/api/publicacoes/" + UUID.randomUUID() + "/anexos/" + anexo).with(como("ADMIN", recepcao)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/publicacoes/" + publicacao + "/anexos/" + anexo).with(como("ADMIN", recepcao)))
                .andExpect(status().isNoContent());
        mvc.perform(get(endereco)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anexo_partes WHERE anexo_id = ?::uuid", Integer.class, anexo)).isZero();
        assertThat(jdbc.queryForList("SELECT acao FROM auditoria WHERE entidade_id = ? ORDER BY momento", String.class, publicacao))
                .containsExactly("ANEXO_ADICIONADO", "ANEXO_ADICIONADO", "ANEXO_EXCLUIDO");
        assertThat(jdbc.queryForObject("SELECT alteracoes -> 'nome' ->> 'antes' FROM auditoria WHERE entidade_id = ? AND acao = 'ANEXO_EXCLUIDO'",
                String.class, publicacao)).isEqualTo("a.jpg");

        mvc.perform(delete("/api/publicacoes/" + publicacao).with(como("ADMIN", recepcao))).andExpect(status().isNoContent());
        assertThat(anexosNoBanco()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anexo_partes p WHERE NOT EXISTS "
                + "(SELECT 1 FROM publicacao_anexos a WHERE a.id = p.anexo_id)", Integer.class)).isZero();
    }

    @Test
    void enviosInterrompidosSaoLimpos() {
        UUID parado = UUID.randomUUID();
        UUID recente = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO publicacao_anexos (id, publicacao_id, tipo, conteudo_tipo, nome, tamanho, situacao, criado_em, criado_por)
                VALUES (?, ?, 'IMAGEM', 'image/jpeg', 'parado.jpg', 1, 'ENVIANDO', now() - interval '2 hours', ?),
                       (?, ?, 'IMAGEM', 'image/jpeg', 'chegando.jpg', 1, 'ENVIANDO', now() - interval '10 minutes', ?)""",
                parado, publicacao, recepcao, recente, publicacao, recepcao);
        jdbc.update("INSERT INTO anexo_partes (anexo_id, ordem, dados) VALUES (?, 0, '\\xffd8ff'::bytea)", parado);

        service.limparEnviosParados();

        assertThat(jdbc.queryForList("SELECT nome FROM publicacao_anexos WHERE publicacao_id = ?", String.class, publicacao))
                .containsExactly("chegando.jpg");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anexo_partes WHERE anexo_id = ?", Integer.class, parado)).isZero();
    }

    @Test
    void oBancoRecusaPedacoGrandeDemaisETipoForaDaLista() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO publicacao_anexos (id, publicacao_id, tipo, conteudo_tipo, nome, tamanho, situacao, criado_em, criado_por)
                VALUES (?, ?, 'IMAGEM', 'image/jpeg', 'a.jpg', 1, 'ENVIANDO', now(), ?)""", id, publicacao, recepcao);
        assertThat(tentar("INSERT INTO anexo_partes (anexo_id, ordem, dados) VALUES ('" + id + "', 0, decode(repeat('00', 1048577), 'hex'))"))
                .contains("anexo_partes_dados_check");
        assertThat(tentar("UPDATE publicacao_anexos SET conteudo_tipo = 'image/svg+xml' WHERE id = '" + id + "'"))
                .contains("publicacao_anexos_conteudo_tipo_check");
    }

    private String tentar(String sql) {
        try {
            jdbc.update(sql);
            return "aceitou";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /**
     * Corrida no teto de 10: com 9 anexos, dois envios passam juntos pela conferência do começo e param no meio;
     * liberados, a conclusão trava a publicação e reconta, e só um entra (o outro, 409, sem deixar pedaço). Várias
     * rodadas, para a corrida não passar por sorte.
     */
    @Test
    void doisEnviosJuntosNoUltimoLugarSoUmEntra() throws Exception {
        for (int rodada = 0; rodada < 5; rodada++) {
            jdbc.update("DELETE FROM publicacao_anexos WHERE publicacao_id = ?", publicacao);
            for (int i = 0; i < AnexoService.MAXIMO_POR_PUBLICACAO - 1; i++) {
                enviado("foto" + i + ".jpg", jpeg(100));
            }
            java.util.concurrent.CountDownLatch noMeio = new java.util.concurrent.CountDownLatch(2);
            java.util.concurrent.CountDownLatch continuar = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.CompletableFuture<String>> envios = new ArrayList<>();
            for (String nome : List.of("a.jpg", "b.jpg")) {
                envios.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        service.receber(publicacao, nome, AnexoLimitesTest.lento(jpeg(MB), noMeio, continuar, jpeg(100)), recepcao);
                        return "entrou";
                    } catch (AnexoRecusadoException e) {
                        return e.status().toString();
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            assertThat(noMeio.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            continuar.countDown();
            List<String> resultados = new ArrayList<>();
            for (var envio : envios) {
                resultados.add(envio.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(resultados).as("rodada %d", rodada).containsExactlyInAnyOrder("entrou", "409 CONFLICT");
            assertThat(anexosNoBanco()).isEqualTo(AnexoService.MAXIMO_POR_PUBLICACAO);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM anexo_partes p WHERE NOT EXISTS "
                    + "(SELECT 1 FROM publicacao_anexos a WHERE a.id = p.anexo_id)", Integer.class)).isZero();
        }
    }
}
