package br.com.academiagestao.auth;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
        // Começa na hora real, e não numa data fixa: o limitador da própria aplicação, neste
        // mesmo contexto, limpa a cada minuto (com o relógio real) as tentativas com mais de
        // 15 minutos. Com uma data fixa no passado, ele apagava as deste teste no meio de uma
        // rodada, e passavam mais tentativas do que o limite (6 em vez de 3, na revisão M3).
        volatile Instant agora = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        void avancar(Duration d) {
            agora = agora.plus(d);
        }

        @Override public Instant instant() { return agora; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    // 3 tentativas por IP por minuto; 2 falhas por conta e IP, e 4 por conta no total, em 15 minutos.
    private static final LimitesDeLoginProperties LIMITES =
            new LimitesDeLoginProperties(3, Duration.ofMinutes(1), 2, 4, Duration.ofMinutes(15));

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transacoes;

    private final RelogioDeTeste relogio = new RelogioDeTeste();
    private LimitadorDeTentativas limitador;

    @BeforeEach
    void preparar() {
        limpeza.apagarTudo();
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
    void falhasDeUmIpBloqueiamSoEsseIpNaConta() {
        limitador.registrarFalha("ana@x.com", "9.9.9.9");
        limitador.registrarFalha("ana@x.com", "9.9.9.9");

        assertThatThrownBy(() -> limitador.verificarERegistrar("9.9.9.9", "ana@x.com"))
                .isInstanceOf(MuitasTentativasException.class);
        // A mesma conta de outro IP (o dono) e outra conta do mesmo IP continuam entrando.
        assertThatCode(() -> limitador.verificarERegistrar("8.8.8.8", "ana@x.com")).doesNotThrowAnyException();
        assertThatCode(() -> limitador.verificarERegistrar("9.9.9.9", "bia@x.com")).doesNotThrowAnyException();

        relogio.avancar(Duration.ofMinutes(15).plusSeconds(1));
        assertThatCode(() -> limitador.verificarERegistrar("9.9.9.9", "ana@x.com")).doesNotThrowAnyException();
    }

    @Test
    void falhasEspalhadasPorIpsBloqueiamAContaParaTodosNoTeto() {
        for (int i = 0; i < 3; i++) {
            limitador.registrarFalha("ana@x.com", "1.1.1." + i);
        }
        // 3 de 4: ainda abaixo do teto.
        assertThatCode(() -> limitador.verificarERegistrar("5.5.5.5", "ana@x.com")).doesNotThrowAnyException();

        limitador.registrarFalha("ana@x.com", "1.1.1.3");
        assertThatThrownBy(() -> limitador.verificarERegistrar("6.6.6.6", "ana@x.com"))
                .isInstanceOf(MuitasTentativasException.class)
                .extracting(e -> ((MuitasTentativasException) e).esperar())
                .isEqualTo(Duration.ofMinutes(15));
        assertThatCode(() -> limitador.verificarERegistrar("6.6.6.6", "bia@x.com")).doesNotThrowAnyException();
    }

    @Test
    void loginCertoZeraSoAsFalhasDoProprioIp() {
        limitador.registrarFalha("ana@x.com", "1.1.1.1");
        limitador.registrarSucesso("ana@x.com", "1.1.1.1");
        limitador.registrarFalha("ana@x.com", "1.1.1.1");
        assertThatCode(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com")).doesNotThrowAnyException();

        // As do atacante, em outro IP, ficam.
        limitador.registrarFalha("ana@x.com", "6.6.6.6");
        limitador.registrarFalha("ana@x.com", "6.6.6.6");
        limitador.registrarSucesso("ana@x.com", "1.1.1.1");
        assertThatThrownBy(() -> limitador.verificarERegistrar("6.6.6.6", "ana@x.com"))
                .isInstanceOf(MuitasTentativasException.class);
    }

    @Test
    void loginCertoNaoZeraOTetoDaConta() {
        // Se zerasse, o ataque espalhado ganharia um teto novo a cada vez que o dono entrasse.
        for (int i = 0; i < 4; i++) {
            limitador.registrarFalha("ana@x.com", "1.1.1." + i);
        }
        limitador.registrarSucesso("ana@x.com", "7.7.7.7");

        assertThatThrownBy(() -> limitador.verificarERegistrar("7.7.7.7", "ana@x.com"))
                .isInstanceOf(MuitasTentativasException.class);
    }

    @Test
    void falhaAntigaSemIpContaSoParaOTeto() {
        // Gravada antes da V19 (sem IP): sai da janela em até 15 min, mas até lá conta no total.
        Timestamp agora = Timestamp.from(relogio.instant());
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO tentativas_login (tipo, chave, momento) VALUES ('CONTA', 'ana@x.com', ?)", agora);
        }
        assertThatThrownBy(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com"))
                .isInstanceOf(MuitasTentativasException.class);
        limpeza.apagarTudo();

        jdbc.update("INSERT INTO tentativas_login (tipo, chave, momento) VALUES ('CONTA', 'ana@x.com', ?)", agora);
        jdbc.update("INSERT INTO tentativas_login (tipo, chave, momento) VALUES ('CONTA', 'ana@x.com', ?)", agora);
        assertThatCode(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com")).doesNotThrowAnyException();
    }

    @Test
    void tentativaPorIpNaoAceitaIpNaColuna() {
        // O IP de uma tentativa por IP é a própria chave; a coluna é só das falhas de conta.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tentativas_login (tipo, chave, ip, momento) VALUES ('IP', '1.1.1.1', '1.1.1.1', now())"))
                .hasMessageContaining("ck_tentativas_login_ip");
    }

    @Test
    void tempoDeEsperaContaAPartirDaFalhaMaisAntiga() {
        limitador.registrarFalha("ana@x.com", "1.1.1.1");
        relogio.avancar(Duration.ofMinutes(5));
        limitador.registrarFalha("ana@x.com", "1.1.1.1");

        assertThatThrownBy(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com"))
                .extracting(e -> ((MuitasTentativasException) e).esperar())
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void comIpEContaBloqueadosOTempoDeEsperaEhOMaior() {
        limitador.registrarFalha("ana@x.com", "1.1.1.1");
        limitador.registrarFalha("ana@x.com", "1.1.1.1");
        for (int i = 0; i < 3; i++) {
            limitador.verificarERegistrar("1.1.1.1", "c" + i + "@x.com");
        }

        assertThatThrownBy(() -> limitador.verificarERegistrar("1.1.1.1", "ana@x.com"))
                .extracting(e -> ((MuitasTentativasException) e).esperar())
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void tentativaBloqueadaNaoContaParaOIp() {
        limitador.registrarFalha("ana@x.com", "1.1.1.1");
        limitador.registrarFalha("ana@x.com", "1.1.1.1");
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
        limitador.registrarFalha("ana@x.com", "1.1.1.1");
        limitador.registrarFalha("ana@x.com", "1.1.1.1");

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
            limitador.registrarFalha("ana@x.com", "1.1.1.1");
            throw new CredenciaisInvalidasException();
        })).isInstanceOf(CredenciaisInvalidasException.class);

        assertThat(registros()).isEqualTo(1);
    }

    @Test
    void limpezaApagaSoOQueJaSaiuDasJanelas() {
        limitador.registrarFalha("antiga@x.com", "1.1.1.1");
        limitador.verificarERegistrar("1.1.1.1", "x@x.com");
        relogio.avancar(Duration.ofMinutes(16));
        limitador.registrarFalha("recente@x.com", "1.1.1.1");

        limitador.limparAntigos();

        assertThat(jdbc.queryForList("SELECT chave FROM tentativas_login", String.class))
                .containsExactly("recente@x.com");
    }

    @Test
    void tentativasSimultaneasDoMesmoIpNaoFuramOLimite() throws Exception {
        // Várias rodadas: uma só pode passar por sorte (sem o lock, uma rodada com o sistema
        // ainda "frio" chegou a dar 3; as seguintes deram 10, o tamanho do pool de conexões).
        for (int rodada = 0; rodada < 10; rodada++) {
            limpeza.apagarTudo();
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
