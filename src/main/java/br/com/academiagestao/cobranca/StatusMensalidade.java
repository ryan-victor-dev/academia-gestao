package br.com.academiagestao.cobranca;

import java.util.EnumSet;
import java.util.Set;

public enum StatusMensalidade {

    PENDENTE,
    VENCIDA,
    PAGA,
    ESTORNADA,
    CANCELADA;

    public Set<StatusMensalidade> proximosPermitidos() {
        return switch (this) {
            case PENDENTE -> EnumSet.of(PAGA, VENCIDA, CANCELADA);
            case VENCIDA -> EnumSet.of(PAGA, CANCELADA);
            case PAGA -> EnumSet.of(ESTORNADA);
            case ESTORNADA, CANCELADA -> EnumSet.noneOf(StatusMensalidade.class);
        };
    }

    public boolean podeIrPara(StatusMensalidade destino) {
        return proximosPermitidos().contains(destino);
    }

    public boolean isFinal() {
        return proximosPermitidos().isEmpty();
    }
}