package br.com.academiagestao.noticias;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static br.com.academiagestao.noticias.AnexoTest.MB;
import static br.com.academiagestao.noticias.AnexoTest.jpeg;
import static br.com.academiagestao.noticias.AnexoTest.mp4;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Os tetos dos anexos (ADR 0047): o espaço total e os envios ao mesmo tempo, com valores pequenos para o teste; e
 * a publicação excluída no meio de um envio, que dá 404 e não 500.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"academia.anexos.espaco-total=3MB", "academia.anexos.envios-ao-mesmo-tempo=1"})
class AnexoLimitesTest {

    @Autowired
    AnexoService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    Publicacoes publicacoes;

    private UUID autor;
    private UUID publicacao;

    @BeforeEach
    void cadastrar() {
        autor = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'R', ?, 'x', 'RECEPCAO')",
                autor, "limites-" + autor + "@teste.local");
        publicacao = novaPublicacao();
        // Outros testes podem ter deixado anexos: o espaço total conta todos.
        jdbc.update("DELETE FROM publicacao_anexos");
    }

    @AfterEach
    void limpar() {
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'PUBLICACAO' AND autor = ?", autor.toString());
        jdbc.update("DELETE FROM publicacoes WHERE criada_por = ?", autor);
        limpeza.apagar("usuarios", autor);
    }

    private UUID novaPublicacao() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO publicacoes (id, tipo, titulo, texto, criada_em, criada_por) VALUES (?, 'NOTICIA', 'P', 'T', now(), ?)",
                id, autor);
        return id;
    }

    /** Entrega o primeiro pedaço e para até {@code continuar} ser liberado (um envio lento pela rede). */
    static InputStream lento(byte[] primeiro, CountDownLatch chegouNoMeio, CountDownLatch continuar, byte[] resto) {
        InputStream trava = new InputStream() {
            private boolean liberado;

            @Override
            public int read() throws IOException {
                esperar();
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                esperar();
                return -1;
            }

            private void esperar() throws IOException {
                if (liberado) {
                    return;
                }
                chegouNoMeio.countDown();
                try {
                    if (!continuar.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("o teste não liberou");
                    }
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                liberado = true;
            }
        };
        return new SequenceInputStream(new SequenceInputStream(new ByteArrayInputStream(primeiro), trava), new ByteArrayInputStream(resto));
    }

    @Test
    void oEspacoTotalNaoPassaDoTeto() throws IOException {
        service.receber(publicacao, "a.mp4", new ByteArrayInputStream(mp4(2 * MB)), autor);

        // O começo cabe (1 MB de 1 MB livre); o resto não: recusado no meio, e nada fica.
        assertThatThrownBy(() -> service.receber(publicacao, "b.mp4", new ByteArrayInputStream(mp4(2 * MB)), autor))
                .isInstanceOfSatisfying(AnexoRecusadoException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE));
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(octet_length(dados)), 0) FROM anexo_partes", Long.class)).isEqualTo(2L * MB);
        // O que ainda cabe entra.
        service.receber(publicacao, "c.jpg", new ByteArrayInputStream(jpeg(500_000)), autor);
        assertThat(jdbc.queryForList("SELECT nome FROM publicacao_anexos ORDER BY criado_em", String.class)).containsExactly("a.mp4", "c.jpg");
    }

    @Test
    void enviosAoMesmoTempoTemTetoEOEspacoVoltaDepois() throws Exception {
        CountDownLatch noMeio = new CountDownLatch(1);
        CountDownLatch continuar = new CountDownLatch(1);
        CompletableFuture<Anexos.Anexo> primeiro = CompletableFuture.supplyAsync(() -> {
            try {
                return service.receber(publicacao, "lento.jpg", lento(jpeg(MB), noMeio, continuar, jpeg(1000)), autor);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(noMeio.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.receber(publicacao, "outro.jpg", new ByteArrayInputStream(jpeg(100)), autor))
                .isInstanceOfSatisfying(AnexoRecusadoException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        // Enquanto chega, o anexo está ENVIANDO e não aparece na publicação.
        assertThat(jdbc.queryForObject("SELECT situacao FROM publicacao_anexos WHERE nome = 'lento.jpg'", String.class)).isEqualTo("ENVIANDO");
        assertThat(publicacoes.buscar(publicacao).orElseThrow().anexos()).isEmpty();

        continuar.countDown();
        assertThat(primeiro.get(10, TimeUnit.SECONDS).tamanho()).isEqualTo(MB + 1000);
        service.receber(publicacao, "depois.jpg", new ByteArrayInputStream(jpeg(100)), autor);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publicacao_anexos WHERE situacao = 'PRONTO'", Integer.class)).isEqualTo(2);
    }

    /**
     * A publicação é excluída enquanto o arquivo chega: a cascata leva o anexo em andamento, e o pedaço seguinte
     * esbarra na chave estrangeira. O envio sai com "publicação não encontrada" (404), e nada sobra.
     */
    @Test
    void publicacaoExcluidaNoMeioDoEnvioDa404ENadaSobra() throws Exception {
        CountDownLatch noMeio = new CountDownLatch(1);
        CountDownLatch continuar = new CountDownLatch(1);
        CompletableFuture<Anexos.Anexo> envio = CompletableFuture.supplyAsync(() -> {
            try {
                return service.receber(publicacao, "video.mp4", lento(mp4(MB), noMeio, continuar, mp4(MB)), autor);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(noMeio.await(10, TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM publicacoes WHERE id = ?", publicacao);
        continuar.countDown();

        assertThatThrownBy(() -> envio.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(PublicacaoNaoEncontradaException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anexo_partes", Integer.class)).isZero();
    }
}
