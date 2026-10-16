package br.com.academiagestao.auth;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
class GeradorDeToken {

    static final String CLAIM_PERFIL = "perfil";

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    GeradorDeToken(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    TokenEmitido gerar(Usuario usuario) {
        Instant agora = Instant.now();
        Instant expiraEm = agora.plus(properties.expiracao());

        // "sub" leva o id, e não o e-mail: o id nunca muda e não é dado pessoal.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.emissor())
                .subject(usuario.getId().toString())
                .issuedAt(agora)
                .expiresAt(expiraEm)
                .claim(CLAIM_PERFIL, usuario.getPerfil().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenEmitido(token, expiraEm);
    }

    record TokenEmitido(String token, Instant expiraEm) {
    }
}
