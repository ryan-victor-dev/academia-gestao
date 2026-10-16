package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableConfigurationProperties(AdminInicialProperties.class)
class SenhaConfig {

    // BCrypt já inclui um salt aleatório em cada hash e é lento de propósito,
    // o que encarece ataques de força bruta caso o banco vaze.
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
