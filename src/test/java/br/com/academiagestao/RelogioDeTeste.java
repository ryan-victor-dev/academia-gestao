package br.com.academiagestao;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Toma o lugar do relógio da aplicação nos testes (ADR 0023). Sem mexer, anda com a hora
 * real. Um teste pode pará-lo ou adiantá-lo para testar regras de data de ponta a ponta, e
 * precisa chamar {@link #restaurar()} no fim: o contexto do Spring é o mesmo para todos.
 * Comece pela hora real (CLAUDE.md): tarefas agendadas, como as limpezas, usam este relógio.
 */
public class RelogioDeTeste extends Clock {

    private volatile Instant parado;
    private volatile Duration adiantado = Duration.ZERO;

    /** Para o relógio neste momento: "hoje" não vira à meia-noite no meio do teste. */
    public void parar() {
        fixar(instant());
    }

    /** Para o relógio num momento escolhido. Prefira o futuro: o passado confunde as limpezas. */
    public void fixar(Instant momento) {
        parado = momento;
        adiantado = Duration.ZERO;
    }

    /** Adianta o relógio (parado ou não). */
    public void adiantar(Duration quanto) {
        adiantado = adiantado.plus(quanto);
    }

    /** Volta à hora real. */
    public void restaurar() {
        parado = null;
        adiantado = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        Instant base = parado;
        return (base == null ? Instant.now() : base).plus(adiantado);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** O mesmo relógio visto de outro fuso (a validação usa o da academia). */
    @Override
    public Clock withZone(ZoneId zone) {
        RelogioDeTeste origem = this;
        return new Clock() {
            @Override
            public Instant instant() {
                return origem.instant();
            }

            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId outra) {
                return origem.withZone(outra);
            }
        };
    }
}
