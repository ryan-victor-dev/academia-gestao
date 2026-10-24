package br.com.academiagestao.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

/** O envio em segundo plano do "esqueci a senha" (ADR 0038): uma thread virtual por e-mail. */
@Configuration
class EnvioDeEmailConfig {

    @Bean(destroyMethod = "close")
    java.util.concurrent.ExecutorService envioDeEmail() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
