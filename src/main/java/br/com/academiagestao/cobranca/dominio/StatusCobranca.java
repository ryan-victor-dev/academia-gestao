package br.com.academiagestao.cobranca.dominio;

import java.util.EnumSet;
import java.util.Set;

/**
 * O que fica guardado (ADR 0024). "Atrasada" não é um estado: é pendente com o vencimento
 * no passado, calculada pela data, como a situação da matrícula.
 */
public enum StatusCobranca {

    PENDENTE,
    PAGA,
    CANCELADA;

    public Set<StatusCobranca> proximosPermitidos() {
        return switch (this) {
            case PENDENTE -> EnumSet.of(PAGA, CANCELADA);
            // Estorno: o pagamento é desfeito e a cobrança volta a ficar em aberto.
            case PAGA -> EnumSet.of(PENDENTE);
            case CANCELADA -> EnumSet.noneOf(StatusCobranca.class);
        };
    }

    public boolean podeIrPara(StatusCobranca destino) {
        return proximosPermitidos().contains(destino);
    }
}
