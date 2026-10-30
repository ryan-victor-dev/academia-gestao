package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({AdminInicialProperties.class, LimitesDeLoginProperties.class,
        SenhasAoMesmoTempoProperties.class})
class SenhaConfig {

    // BCrypt já inclui um salt aleatório em cada hash e é lento de propósito,
    // o que encarece ataques de força bruta caso o banco vaze. Por ser lento, tem um teto
    // de contas ao mesmo tempo (ADR 0046, item 3).
    @Bean
    PasswordEncoder passwordEncoder(SenhasAoMesmoTempoProperties senhas, Clock relogio) {
        return new SenhasComTeto(new BCryptPasswordEncoder(), senhas.tetoEmUso(), senhas.espera(), relogio);
    }
}
