package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuração do token de acesso.
 *
 * @param segredo   chave HMAC em Base64 (variável JWT_SEGREDO), com pelo menos 256 bits
 * @param expiracao validade do token; curta para limitar o estrago de um token vazado
 * @param emissor   valor da claim "iss", conferido na validação
 */
@ConfigurationProperties(prefix = "academia.jwt")
record JwtProperties(
        String segredo,
        @DefaultValue("15m") Duration expiracao,
        @DefaultValue("academia-gestao") String emissor) {
}
