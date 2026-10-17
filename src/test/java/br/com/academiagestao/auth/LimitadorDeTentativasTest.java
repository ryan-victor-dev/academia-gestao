package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Limitador com os contadores no Postgres, e um relógio que o teste avança na mão. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LimitadorDeTentativasTest {

    static final class RelogioDeTeste extends Clock {
        volatile Instant agora = Instant.parse("2026-10-16T12:00:00Z");

        void avancar(Duration d) {
            agora = agora.plus(d);
        }

        @Override public Instant instant() { return agora; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    // 3 tentativas por IP por minuto; 2 falhas por conta em 15 minutos.
    private static final LimitesDeLoginProperties LIMITES =
            new LimitesDeLoginProperties(3, Duration.ofMinutes(1), 2, Duration.ofMinutes(15));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transacoes;

    private final RelogioDeTeste relogio = new RelogioDeTeste();
    private LimitadorDeTentativas limitador;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM tentativas_login");
        limitador = novoLimitador();
    }

    private LimitadorDeTentativas novoLimitador() {
        return new LimitadorDeTentativas(LIMITES, jdbc, transacoes, relogio);
    }

    private int registros() {
        return jdbc.queryForObject("SELECT count(*) FROM tentativas_login", Integer.class);
    }

    @Test
    void ipPassaDoLimiteEEsperaAJanelaPassar() {
        for (int i = 0; i < 3; i++) {
            limitador.verificarERegistrar("1.1.1.1", "conta" + i + "@x.com");
        }
        assertThatThrownBy(() -> limitador.verificarERegistrar("1.1.1.1", "outra@x.com"))
                .isInstanceOf(MuitasTentativasException.class)
                .extracting(e -> ((MuitasTentativasException) e).esperar())
                .isEqualTo(Duration.ofMinutes(1));

        relogio.avancar(Duration.ofSeconds(61));
        assertThatCode(() -> limitador.verificarERegistrar("1.1.1.1", "outra@x.com")).doesNotThrowAnyException();
    }

    @Test
    void outroIpNaoEhAfetado() {
        for (int i = 0; i < 3; i++) {
            limitador.verificarERegistrar("1.1.1.1", "a@x.com");
        }
        assertThatCode(() -> limitador.verificarERegistrar("2.2.2.2", "b@x.com")).doesNotThrowAnyException();
    }

    @Test
    void contaComFalhasDemaisFicaBloqueadaParaQualquerIp() {
        limitador.registrarFalha("ana@x.com");
        limitador.registrarFalha("ana@x.com");

        assertThatThrownBy(() -> limitador.verificarERegistrar("9.9.9.9", "ana@x.com"))
                .isInstanceOf(MuitasTentativasException.class);
        assertThatCode(() -> limitador.verificarERegistrar("9.9.9.9", "bia@x.com")).doesNotThrowAnyException();

        relogio.avancar(Duration.ofMinutes(15).plusSeconds(1));
        assertThatCode(() -> limitador.verificarERegistrar("9.9.9.9", "ana@x.com")).doesNotThrowAnyException();
    }

    @Test
    void loginCertoZeraAsFalhasDaConta() {
        limitador.registrarFalha("ana@x.com");
        limitador.registrarSucesso("ana@x.com");
        limitador.registrarFalha("ana@x.com");

        assertThatCode(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com")).doesNotThrowAnyException();
    }

    @Test
    void tempoDeEsperaContaAPartirDaFalhaMaisAntiga() {
        limitador.registrarFalha("ana@x.com");
        relogio.avancar(Duration.ofMinutes(5));
        limitador.registrarFalha("ana@x.com");

        assertThatThrownBy(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com"))
                .extracting(e -> ((MuitasTentativasException) e).esperar())
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void comIpEContaBloqueadosOTempoDeEsperaEhOMaior() {
        limitador.registrarFalha("ana@x.com");
        limitador.registrarFalha("ana@x.com");
        for (int i = 0; i < 3; i++) {
            limitador.verificarERegistrar("1.1.1.1", "c" + i + "@x.com");
        }

        assertThatThrownBy(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com"))
                .extracting(e -> ((MuitasTentativasException) e).esperar())
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void tentativaBloqueadaNaoContaParaOIp() {
        limitador.registrarFalha("ana@x.com");
        limitador.registrarFalha("ana@x.com");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com"))
                    .isInstanceOf(MuitasTentativasException.class);
        }
        for (int i = 0; i < 3; i++) {
            limitador.verificarERegistrar("1.1.1.1", "c" + i + "@x.com");
        }
    }

    @Test
    void bloqueioSobreviveAoReinicioDaAplicacao() {
        limitador.registrarFalha("ana@x.com");
        limitador.registrarFalha("ana@x.com");

        // Reiniciar a aplicação = um limitador novo, sem nada em memória, sobre o mesmo banco.
        LimitadorDeTentativas depoisDoReinicio = novoLimitador();

        assertThatThrownBy(() -> depoisDoReinicio.verificarERegistrar("1.1.1.1", "ana@x.com"))
                .isInstanceOf(MuitasTentativasException.class);
    }

    @Test
    void falhaFicaRegistradaMesmoQuandoATransacaoDoLoginEhDesfeita() {
        // O login roda numa transação que é desfeita quando a senha está errada.
        TransactionTemplate transacaoDoLogin = new TransactionTemplate(transacoes);
        assertThatThrownBy(() -> transacaoDoLogin.executeWithoutResult(status -> {
            limitador.registrarFalha("ana@x.com");
            throw new CredenciaisInvalidasException();
        })).isInstanceOf(CredenciaisInvalidasException.class);

        assertThat(registros()).isEqualTo(1);
    }

    @Test
    void limpezaApagaSoOQueJaSaiuDasJanelas() {
        limitador.registrarFalha("antiga@x.com");
        limitador.verificarERegistrar("1.1.1.1", "x@x.com");
        relogio.avancar(Duration.ofMinutes(16));
        limitador.registrarFalha("recente@x.com");

        limitador.limparAntigos();

        assertThat(jdbc.queryForList("SELECT chave FROM tentativas_login", String.class))
                .containsExactly("recente@x.com");
    }

    @Test
    void tentativasSimultaneasDoMesmoIpNaoFuramOLimite() throws Exception {
        // Várias rodadas: uma só pode passar por sorte (sem o lock, uma rodada com o sistema
        // ainda "frio" chegou a dar 3; as seguintes deram 10, o tamanho do pool de conexões).
        for (int rodada = 0; rodada < 10; rodada++) {
            jdbc.update("DELETE FROM tentativas_login");
            assertThat(rodadaSimultanea(30)).as("tentativas que passaram na rodada %d", rodada).isEqualTo(3);
        }
    }

    private int rodadaSimultanea(int quantas) throws Exception {
        AtomicInteger passaram = new AtomicInteger();
        CountDownLatch largada = new CountDownLatch(1);
        CompletableFuture<?>[] tentativas = new CompletableFuture<?>[quantas];
        for (int i = 0; i < tentativas.length; i++) {
            String conta = "c" + i + "@x.com";
            tentativas[i] = CompletableFuture.runAsync(() -> {
                try {
                    largada.await();
                    limitador.verificarERegistrar("7.7.7.7", conta);
                    passaram.incrementAndGet();
                } catch (MuitasTentativasException e) {
                    // esperado para quem passou do limite
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        largada.countDown();
        CompletableFuture.allOf(tentativas).get(30, TimeUnit.SECONDS);
        return passaram.get();
    }
}
