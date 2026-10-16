package br.com.academiagestao.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

    // HS256 exige uma chave de pelo menos 256 bits (32 bytes).
    static final int TAMANHO_MINIMO_CHAVE_BYTES = 32;

    /**
     * Chave simétrica (HMAC): a mesma aplicação assina e valida o token,
     * então não precisamos de par de chaves pública/privada.
     */
    @Bean
    SecretKey chaveJwt(JwtProperties properties) {
        String segredo = properties.segredo();
        if (segredo == null || segredo.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SEGREDO não definido. Gere um com: openssl rand -base64 32");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(segredo.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SEGREDO precisa estar em Base64", e);
        }
        if (bytes.length < TAMANHO_MINIMO_CHAVE_BYTES) {
            throw new IllegalStateException(
                    "JWT_SEGREDO precisa ter pelo menos " + TAMANHO_MINIMO_CHAVE_BYTES + " bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey chaveJwt) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chaveJwt));
    }
}
