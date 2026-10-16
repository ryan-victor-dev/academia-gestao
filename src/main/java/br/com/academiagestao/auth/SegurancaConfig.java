package br.com.academiagestao.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

import javax.crypto.SecretKey;

@Configuration
@EnableMethodSecurity // liga o @PreAuthorize nos controllers
class SegurancaConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationConverter jwtAuthenticationConverter,
                                            AuthenticationEntryPoint entryPoint,
                                            AccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                // CSRF explora o envio automático de cookies pelo navegador. Esta API não usa
                // cookie: o token vai no cabeçalho Authorization, que o navegador não anexa
                // sozinho. Por isso, e só por isso, a proteção pode ser desligada.
                .csrf(csrf -> csrf.disable())
                // Nada de sessão no servidor: cada requisição se identifica pelo próprio token.
                .sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(regras -> regras
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // Deixa o Spring montar a página de erro de uma rota já autorizada.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(erros -> erros
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    /*
     * Os erros de segurança acontecem nos filtros, antes de chegar a um controller, e por
     * padrão saem sem corpo. Aqui eles são repassados ao mesmo mecanismo de
     * @ExceptionHandler da API (AuthExceptionHandler), para virarem ProblemDetail em JSON
     * igual a qualquer outro erro.
     */

    /** 401: sem token, token inválido ou expirado. */
    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        // Mantém o cabeçalho WWW-Authenticate padrão (RFC 6750), que diz ao cliente o motivo.
        BearerTokenAuthenticationEntryPoint padrao = new BearerTokenAuthenticationEntryPoint();
        return (request, response, excecao) -> {
            padrao.commence(request, response, excecao);
            resolver.resolveException(request, response, null, excecao);
        };
    }

    /** 403: token válido, mas o perfil não tem permissão. */
    @Bean
    AccessDeniedHandler accessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, excecao) -> resolver.resolveException(request, response, null, excecao);
    }

    /** Valida assinatura, expiração ("exp", com 60 s de tolerância) e emissor ("iss"). */
    @Bean
    JwtDecoder jwtDecoder(SecretKey chaveJwt, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(chaveJwt)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.emissor()));
        return decoder;
    }

    /** Claim "perfil": "ADMIN" vira a autoridade ROLE_ADMIN, que é o que hasRole("ADMIN") procura. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter perfis = new JwtGrantedAuthoritiesConverter();
        perfis.setAuthoritiesClaimName(ClaimsDoToken.PERFIL);
        perfis.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(perfis);
        return converter;
    }
}
