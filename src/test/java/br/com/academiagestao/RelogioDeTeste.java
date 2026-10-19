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
        parado = instant();
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

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("o relógio da aplicação é sempre UTC");
    }
}
