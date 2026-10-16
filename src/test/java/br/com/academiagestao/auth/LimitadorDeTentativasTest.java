package br.com.academiagestao.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitadorDeTentativasTest {

    /** Relógio que o teste avança na mão, para testar janelas de tempo sem esperar. */
    static final class RelogioDeTeste extends Clock {
        Instant agora = Instant.parse("2026-10-16T12:00:00Z");

        void avancar(Duration d) {
            agora = agora.plus(d);
        }

        @Override public Instant instant() { return agora; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    private final RelogioDeTeste relogio = new RelogioDeTeste();
    // 3 tentativas por IP por minuto; 2 falhas por conta em 15 minutos.
    private final LimitadorDeTentativas limitador = new LimitadorDeTentativas(
            new LimitesDeLoginProperties(3, Duration.ofMinutes(1), 2, Duration.ofMinutes(15)), relogio);

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
        // Esgota o IP com outras contas (a da Ana já está bloqueada e não registraria).
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
        // O IP continua com as 3 vagas: só tentativas que chegaram a conferir senha contam.
        for (int i = 0; i < 3; i++) {
            limitador.verificarERegistrar("1.1.1.1", "c" + i + "@x.com");
        }
    }

    @Test
    void registrosAntigosSaoLimposQuandoOMapaCresceDemais() {
        for (int i = 0; i < 10_001; i++) {
            limitador.verificarERegistrar("10.0." + (i / 256) + "." + (i % 256), "c" + i + "@x.com");
        }
        relogio.avancar(Duration.ofMinutes(2));

        limitador.verificarERegistrar("1.1.1.1", "nova@x.com");

        assertThat(limitador.tamanhoDosRegistros()).isLessThan(10);
    }
}
