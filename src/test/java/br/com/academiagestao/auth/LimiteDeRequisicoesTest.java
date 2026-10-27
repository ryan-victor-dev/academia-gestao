package br.com.academiagestao.auth;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Balde de fichas por usuário, com relógio controlado. */
class LimiteDeRequisicoesTest {

    private static final Instant INICIO = Instant.parse("2026-10-17T12:00:00Z");

    /** Relógio que só anda quando o teste manda. */
    private static final class Relogio extends Clock {
        private Instant agora = INICIO;

        void andar(Duration tempo) {
            agora = agora.plus(tempo);
        }

        @Override public Instant instant() { return agora; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zona) { return this; }
    }

    private final Relogio relogio = new Relogio();

    private LimiteDeRequisicoes limite(int porMinuto) {
        return new LimiteDeRequisicoes(new LimiteDeRequisicoesProperties(porMinuto), relogio);
    }

    @Test
    void aceitaUmaRajadaDoTamanhoDoLimiteERecusaAProxima() {
        LimiteDeRequisicoes limite = limite(60);
        for (int i = 0; i < 60; i++) {
            assertThat(limite.consumir("ana").liberada()).as("requisição %d", i + 1).isTrue();
        }

        LimiteDeRequisicoes.Resultado recusada = limite.consumir("ana");
        assertThat(recusada.liberada()).isFalse();
        // 60 por minuto = uma ficha por segundo.
        assertThat(recusada.esperar()).isEqualTo(Duration.ofSeconds(1));
    }

    /** Um ajuste do NTP (ou o relógio de teste restaurado) não pode prender o usuário. */
    @Test
    void relogioQueVoltaNaoPrendeOUsuario() {
        LimiteDeRequisicoes limite = limite(60);
        relogio.andar(Duration.ofDays(1));
        for (int i = 0; i < 60; i++) {
            limite.consumir("ana");
        }
        assertThat(limite.consumir("ana").avisar()).isTrue();

        relogio.andar(Duration.ofDays(-1));

        // O balde recomeça cheio, em vez de esperar um dia; e o log volta a avisar.
        for (int i = 0; i < 60; i++) {
            assertThat(limite.consumir("ana").liberada()).as("requisição %d", i + 1).isTrue();
        }
        LimiteDeRequisicoes.Resultado recusada = limite.consumir("ana");
        assertThat(recusada.esperar()).isEqualTo(Duration.ofSeconds(1));
        assertThat(recusada.avisar()).isTrue();
    }

    /** Voltar menos de uma janela mantém o que foi gasto: nada de fichas de graça. */
    @Test
    void relogioQueVoltaPoucoNaoDevolveFichas() {
        LimiteDeRequisicoes limite = limite(60);
        relogio.andar(Duration.ofSeconds(30));
        for (int i = 0; i < 30; i++) {
            limite.consumir("ana");
        }

        relogio.andar(Duration.ofSeconds(-30));

        // Os 30 s em que as fichas voltariam "não aconteceram": o balde está vazio.
        assertThat(limite.consumir("ana").esperar()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void asFichasVoltamAosPoucosERecusaNaoGastaFicha() {
        LimiteDeRequisicoes limite = limite(60);
        for (int i = 0; i < 60; i++) {
            limite.consumir("ana");
        }
        // Insistir sem esperar não adianta, mas também não atrasa a próxima ficha.
        for (int i = 0; i < 10; i++) {
            assertThat(limite.consumir("ana").liberada()).isFalse();
        }

        relogio.andar(Duration.ofMillis(999));
        assertThat(limite.consumir("ana").esperar()).isEqualTo(Duration.ofMillis(1));
        relogio.andar(Duration.ofMillis(1));
        assertThat(limite.consumir("ana").liberada()).isTrue();
        assertThat(limite.consumir("ana").liberada()).isFalse();

        relogio.andar(Duration.ofSeconds(3));
        assertThat(limite.consumir("ana").liberada()).isTrue();
        assertThat(limite.consumir("ana").liberada()).isTrue();
        assertThat(limite.consumir("ana").liberada()).isTrue();
        assertThat(limite.consumir("ana").liberada()).isFalse();
    }

    /** Conferir (antes do banco, diagnóstico de 18/10, S4) diz o mesmo que consumir, sem gastar. */
    @Test
    void conferirNaoGastaFichaERecusaComoConsumir() {
        LimiteDeRequisicoes limite = limite(2);
        for (int i = 0; i < 10; i++) {
            assertThat(limite.conferir("ana").liberada()).isTrue();
        }
        assertThat(limite.usuariosAcompanhados()).as("conferir não cria balde").isZero();

        limite.consumir("ana");
        assertThat(limite.conferir("ana").liberada()).isTrue();
        limite.consumir("ana");

        LimiteDeRequisicoes.Resultado recusada = limite.conferir("ana");
        assertThat(recusada.liberada()).isFalse();
        // 2 por minuto = uma ficha a cada 30 s; o aviso no log sai uma vez, como no consumir.
        assertThat(recusada.esperar()).isEqualTo(Duration.ofSeconds(30));
        assertThat(recusada.avisar()).isTrue();
        assertThat(limite.consumir("ana").avisar()).isFalse();

        relogio.andar(Duration.ofSeconds(30));
        assertThat(limite.conferir("ana").liberada()).isTrue();
        assertThat(limite.consumir("ana").liberada()).isTrue();
        assertThat(limite.conferir("ana").liberada()).isFalse();
    }

    @Test
    void oBaldeNaoPassaDoLimiteMesmoDepoisDeMuitoTempoParado() {
        LimiteDeRequisicoes limite = limite(60);
        limite.consumir("ana");
        relogio.andar(Duration.ofHours(5));

        for (int i = 0; i < 60; i++) {
            assertThat(limite.consumir("ana").liberada()).isTrue();
        }
        assertThat(limite.consumir("ana").liberada()).isFalse();
    }

    @Test
    void cadaUsuarioTemOSeuBalde() {
        LimiteDeRequisicoes limite = limite(2);
        limite.consumir("ana");
        limite.consumir("ana");
        assertThat(limite.consumir("ana").liberada()).isFalse();

        assertThat(limite.consumir("bia").liberada()).isTrue();
        assertThat(limite.consumir("bia").liberada()).isTrue();
    }

    /** Quem insiste ganha uma ficha a cada segundo e é recusado de novo: o log avisa uma vez por minuto. */
    @Test
    void avisaNoMaximoUmaVezPorMinuto() {
        LimiteDeRequisicoes limite = limite(60);
        for (int i = 0; i < 60; i++) {
            limite.consumir("ana");
        }
        assertThat(limite.consumir("ana").avisar()).isTrue();
        assertThat(limite.consumir("ana").avisar()).isFalse();

        for (int segundo = 1; segundo < 60; segundo++) {
            relogio.andar(Duration.ofSeconds(1));
            assertThat(limite.consumir("ana").liberada()).isTrue();
            assertThat(limite.consumir("ana").avisar()).as("segundo %d", segundo).isFalse();
        }
        relogio.andar(Duration.ofSeconds(1));
        assertThat(limite.consumir("ana").liberada()).isTrue();
        assertThat(limite.consumir("ana").avisar()).as("um minuto depois do aviso").isTrue();
        assertThat(limite.consumir("bia").avisar()).as("Bia nem foi recusada").isFalse();
    }

    @Test
    void limpezaTiraSoQuemJaEstaComOBaldeCheio() {
        LimiteDeRequisicoes limite = limite(60);
        for (int i = 0; i < 60; i++) {
            limite.consumir("ana");
            limite.consumir("bia");
        }
        limite.consumir("cris");
        relogio.andar(Duration.ofSeconds(60));

        limite.limparCheios();

        // Ana e Bia gastaram tudo há 60 s (já encheram); Cris, uma ficha (encheu em 1 s).
        assertThat(limite.usuariosAcompanhados()).isZero();

        for (int i = 0; i < 60; i++) {
            limite.consumir("ana");
        }
        relogio.andar(Duration.ofSeconds(59));
        limite.limparCheios();
        assertThat(limite.usuariosAcompanhados()).as("Ana ainda não encheu").isEqualTo(1);
        for (int i = 0; i < 59; i++) {
            assertThat(limite.consumir("ana").liberada()).isTrue();
        }
        assertThat(limite.consumir("ana").liberada()).as("Ana continua com o balde de antes").isFalse();
    }

    @Test
    void limiteMenorQueUmNaoSobe() {
        assertThatThrownBy(() -> limite(0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("por-minuto");
        assertThat(limite(1).consumir("ana").liberada()).isTrue();
    }

    /** Muitas requisições do mesmo usuário ao mesmo tempo: nenhuma ficha é gasta duas vezes. */
    @RepeatedTest(5)
    void requisicoesSimultaneasNaoPassamDoLimite() throws Exception {
        LimiteDeRequisicoes limite = limite(300);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger liberadas = new AtomicInteger();
        List<CompletableFuture<Void>> tarefas = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(32)) {
            for (int i = 0; i < 1_000; i++) {
                tarefas.add(CompletableFuture.runAsync(() -> {
                    try {
                        largada.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (limite.consumir("ana").liberada()) {
                        liberadas.incrementAndGet();
                    }
                }, executor));
            }
            largada.countDown();
            CompletableFuture.allOf(tarefas.toArray(CompletableFuture[]::new)).get();
        }
        assertThat(liberadas.get()).isEqualTo(300);
    }
}
