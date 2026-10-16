package br.com.academiagestao.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

/**
 * Recusa token de usuário removido, desativado, ou emitido antes da última troca de
 * senha/desativação (versão diferente da atual). É uma consulta por chave primária a
 * cada requisição: o preço de poder derrubar um token na hora.
 */
class TokenRevogadoValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOGADO =
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Token revogado", null);

    private final UsuarioRepository repository;

    TokenRevogadoValidator(UsuarioRepository repository) {
        this.repository = repository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Optional<Integer> versaoAtual = idDoUsuario(jwt).flatMap(repository::versaoTokenSeAtivo);
        Object versaoDoToken = jwt.getClaims().get(ClaimsDoToken.VERSAO_TOKEN);

        boolean valido = versaoAtual.isPresent()
                && versaoDoToken instanceof Number numero
                && numero.longValue() == versaoAtual.get();
        return valido ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(REVOGADO);
    }

    private static Optional<UUID> idDoUsuario(Jwt jwt) {
        try {
            return Optional.of(UUID.fromString(jwt.getSubject()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
