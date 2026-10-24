package br.com.academiagestao.pagamentos;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Liga as propriedades do pagamento online (ADR 0034). */
@Configuration
@EnableConfigurationProperties(PagamentosProperties.class)
class PagamentosConfig {
}
