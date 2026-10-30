package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Teto de senhas conferidas (ou cifradas) ao mesmo tempo pelo BCrypt (ADR 0046, item 3). Ver
 * {@link SenhasComTeto}.
 *
 * @param teto   quantas ao mesmo tempo; 0 = o número de núcleos da máquina (o do container, com
 *               o limite de CPU do Compose). Cada uma ocupa um núcleo inteiro por uns 50 a 100 ms.
 * @param espera quanto um login espera por uma vaga antes do 503: absorve a rajada normal (a
 *               academia abrindo), sem formar uma fila longa numa enxurrada
 */
@ConfigurationProperties(prefix = "academia.senhas")
record SenhasAoMesmoTempoProperties(@DefaultValue("0") int teto, @DefaultValue("500ms") Duration espera) {

    SenhasAoMesmoTempoProperties {
        if (teto < 0) {
            throw new IllegalArgumentException("academia.senhas.teto não pode ser negativo (0 = o número de núcleos)");
        }
        if (espera.isNegative()) {
            throw new IllegalArgumentException("academia.senhas.espera não pode ser negativa");
        }
    }

    /** O teto em uso: o configurado, ou o número de núcleos. */
    int tetoEmUso() {
        return teto > 0 ? teto : Runtime.getRuntime().availableProcessors();
    }
}
