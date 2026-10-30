package br.com.academiagestao.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O teto de contas do BCrypt ao mesmo tempo (ADR 0046, item 3). */
@ExtendWith(OutputCaptureExtension.class)
class SenhasComTetoTest {

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);

    /** Um "BCrypt" que segura a vaga até o teste soltar, e conta quantas contas fez. */
    private static final class Lento implements PasswordEncoder {
        final CountDownLatch soltar = new CountDownLatch(1);
        final CountDownLatch dentro;
        Lento(int esperadas) {
            dentro = new CountDownLatch(esperadas);
        }
        @Override
        public String encode(CharSequence senha) {
            esperar();
            return "hash:" + senha;
        }
        @Override
        public boolean matches(CharSequence senha, String hash) {
            esperar();
            return hash.equals("hash:" + senha);
        }
        @Override
        public boolean upgradeEncoding(String hash) {
            return hash.startsWith("velho");
        }
        private void esperar() {
            dentro.countDown();
            try {
                soltar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final List<CompletableFuture<?>> emAndamento = new ArrayList<>();

    @AfterEach
    void esperarAsQueSobraram() {
        emAndamento.forEach(CompletableFuture::join);
    }

    private CompletableFuture<Boolean> conferirAoLado(PasswordEncoder senhas) {
        CompletableFuture<Boolean> conta = CompletableFuture.supplyAsync(() -> senhas.matches("s", "hash:s"));
        emAndamento.add(conta);
        return conta;
    }

    @Test
    void acimaDoTetoRecusaDepoisDaEspera() throws Exception {
        Lento lento = new Lento(2);
        SenhasComTeto senhas = new SenhasComTeto(lento, 2, Duration.ofMillis(100), RELOGIO);
        conferirAoLado(senhas);
        conferirAoLado(senhas);
        assertThat(lento.dentro.await(5, TimeUnit.SECONDS)).isTrue();

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> senhas.encode("x")).isInstanceOf(MuitasSenhasAoMesmoTempoException.class);
        assertThatThrownBy(() -> senhas.matches("x", "hash:x")).isInstanceOf(MuitasSenhasAoMesmoTempoException.class);
        // Esperou pela vaga (as duas recusas), mas não para sempre.
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isBetween(Duration.ofMillis(190), Duration.ofSeconds(3));

        lento.soltar.countDown();
        assertThat(senhas.matches("x", "hash:x")).isTrue();
        assertThat(senhas.encode("y")).isEqualTo("hash:y");
    }

    @Test
    void quemEsperaPegaAVagaQueSoltouDentroDaEspera() throws Exception {
        Lento lento = new Lento(1);
        SenhasComTeto senhas = new SenhasComTeto(lento, 1, Duration.ofSeconds(5), RELOGIO);
        conferirAoLado(senhas);
        assertThat(lento.dentro.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> naFila = conferirAoLado(senhas);
        Thread.sleep(100);
        assertThat(naFila).isNotDone();
        lento.soltar.countDown();

        assertThat(naFila.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void erroNaContaDevolveAVaga() {
        PasswordEncoder quebrado = new PasswordEncoder() {
            @Override
            public String encode(CharSequence senha) {
                throw new IllegalStateException("quebrou");
            }
            @Override
            public boolean matches(CharSequence senha, String hash) {
                throw new IllegalStateException("quebrou");
            }
        };
        SenhasComTeto senhas = new SenhasComTeto(quebrado, 1, Duration.ZERO, RELOGIO);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> senhas.matches("x", "y")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> senhas.encode("x")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void interrompidaNaEsperaRecusaEContinuaInterrompida() throws Exception {
        Lento lento = new Lento(1);
        SenhasComTeto senhas = new SenhasComTeto(lento, 1, Duration.ofSeconds(30), RELOGIO);
        conferirAoLado(senhas);
        assertThat(lento.dentro.await(5, TimeUnit.SECONDS)).isTrue();

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> senhas.matches("x", "hash:x")).isInstanceOf(MuitasSenhasAoMesmoTempoException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            lento.soltar.countDown();
        }
    }

    @Test
    void repassaOResultadoDoBcrypt() {
        Lento lento = new Lento(0);
        lento.soltar.countDown();
        SenhasComTeto senhas = new SenhasComTeto(lento, 1, Duration.ZERO, RELOGIO);

        assertThat(senhas.encode("abc")).isEqualTo("hash:abc");
        assertThat(senhas.matches("abc", "hash:abc")).isTrue();
        assertThat(senhas.matches("abc", "hash:outra")).isFalse();
        assertThat(senhas.upgradeEncoding("velho")).isTrue();
        assertThat(senhas.upgradeEncoding("novo")).isFalse();
    }

    @Test
    void avisaNoLogNoMaximoUmaVezPorMinuto(CapturedOutput saida) throws Exception {
        Lento lento = new Lento(1);
        Instant[] agora = {Instant.parse("2026-10-30T12:00:00Z")};
        Clock relogio = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }
            @Override
            public Clock withZone(java.time.ZoneId zona) {
                return this;
            }
            @Override
            public Instant instant() {
                return agora[0];
            }
        };
        SenhasComTeto senhas = new SenhasComTeto(lento, 1, Duration.ZERO, relogio);
        conferirAoLado(senhas);
        assertThat(lento.dentro.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> senhas.matches("x", "y")).isInstanceOf(MuitasSenhasAoMesmoTempoException.class);
            }
            assertThat(avisos(saida)).isEqualTo(1);
            agora[0] = agora[0].plusSeconds(59);
            assertThatThrownBy(() -> senhas.matches("x", "y")).isInstanceOf(MuitasSenhasAoMesmoTempoException.class);
            assertThat(avisos(saida)).isEqualTo(1);
            agora[0] = agora[0].plusSeconds(2);
            assertThatThrownBy(() -> senhas.matches("x", "y")).isInstanceOf(MuitasSenhasAoMesmoTempoException.class);
            assertThat(avisos(saida)).isEqualTo(2);
        } finally {
            lento.soltar.countDown();
        }
    }

    private static long avisos(CapturedOutput saida) {
        return saida.getOut().lines().filter(l -> l.contains("Teto de senhas ao mesmo tempo atingido (1)")).count();
    }

    @Test
    void oTetoPadraoEONumeroDeNucleos() {
        assertThat(new SenhasAoMesmoTempoProperties(0, Duration.ofMillis(500)).tetoEmUso())
                .isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(new SenhasAoMesmoTempoProperties(7, Duration.ofMillis(500)).tetoEmUso()).isEqualTo(7);
        assertThatThrownBy(() -> new SenhasAoMesmoTempoProperties(-1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SenhasAoMesmoTempoProperties(1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
