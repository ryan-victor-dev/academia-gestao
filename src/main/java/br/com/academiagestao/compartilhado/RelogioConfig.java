package br.com.academiagestao.compartilhado;

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * O relógio único da aplicação: todo "agora" sai deste {@link Clock}, injetado em quem
 * precisa (ADR 0023). Nos testes, um relógio controlável toma o lugar dele, e as regras de
 * data (vencimento, fim da matrícula) são testadas de ponta a ponta numa data escolhida.
 */
@Configuration(proxyBeanMethods = false)
class RelogioConfig {

    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }

    /** O {@code @Past} dos pedidos também usa este relógio, e no fuso da academia. */
    @Bean
    ValidationConfigurationCustomizer relogioNaValidacao(Clock relogio) {
        return configuracao -> configuracao.clockProvider(() -> relogio.withZone(Calendario.FUSO));
    }
}
