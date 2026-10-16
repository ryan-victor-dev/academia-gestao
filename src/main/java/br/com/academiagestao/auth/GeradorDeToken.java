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
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.emissor())
                .subject(usuario.getId().toString())
                .issuedAt(agora)
                .expiresAt(expiraEm)
                .claim(ClaimsDoToken.PERFIL, usuario.getPerfil().name());
        // Login de aluno leva o id do cadastro dele. Assim o módulo de alunos sabe de quem
        // são os dados sem precisar consultar as tabelas do módulo de autenticação.
        if (usuario.getAlunoId() != null) {
            claims.claim(ClaimsDoToken.ALUNO_ID, usuario.getAlunoId().toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new TokenEmitido(token, expiraEm);
    }

    record TokenEmitido(String token, Instant expiraEm) {
    }
}
