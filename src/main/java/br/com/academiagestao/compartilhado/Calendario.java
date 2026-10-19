package br.com.academiagestao.compartilhado;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * O dia na academia. As regras de data (início da matrícula, nascimento) usam o fuso dela, e
 * não o do servidor: às 22h de São Paulo, em UTC já é amanhã (ADR 0023).
 */
public final class Calendario {

    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private Calendario() {
    }

    /** O dia na academia no momento dado (que vem do relógio da aplicação). */
    public static LocalDate hoje(Instant agora) {
        return LocalDate.ofInstant(agora, FUSO);
    }
}
