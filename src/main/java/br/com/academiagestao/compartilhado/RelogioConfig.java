package br.com.academiagestao.compartilhado;

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
}
