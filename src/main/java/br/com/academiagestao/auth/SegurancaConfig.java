package br.com.academiagestao.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.servlet.HandlerExceptionResolver;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

// Só existe com servidor web; o comando de recuperação de ADMIN sobe sem ele.
@Configuration
@ConditionalOnWebApplication
@EnableMethodSecurity // liga o @PreAuthorize nos controllers
@EnableConfigurationProperties(LimiteDeRequisicoesProperties.class)
class SegurancaConfig {

    /** Autoridade de quem entrou com senha temporária; substitui o ROLE_ do perfil. */
    static final String SENHA_TEMPORARIA = "SENHA_TEMPORARIA";

    /** Autoridade do login de aluno inativo (ADR 0029); vem junto com o ROLE_ALUNO. */
    static final String SOMENTE_LEITURA = "SOMENTE_LEITURA";

    /**
     * O login só de leitura passa só em consulta (GET), em qualquer rota que o perfil já
     * permitiria; nada que altere, nem nas rotas que forem criadas no futuro.
     */
    static final AuthorizationManager<RequestAuthorizationContext> SO_CONSULTA_SE_SOMENTE_LEITURA =
            (autenticacao, contexto) -> new AuthorizationDecision(
                    HttpMethod.GET.matches(contexto.getRequest().getMethod())
                            || autenticacao.get().getAuthorities().stream()
                                    .noneMatch(a -> SOMENTE_LEITURA.equals(a.getAuthority())));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationConverter jwtAuthenticationConverter,
                                            AuthenticationEntryPoint entryPoint,
                                            AccessDeniedHandler accessDeniedHandler,
                                            LimiteDeRequisicoes limiteDeRequisicoes,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
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
                        // Quem entrou com senha temporária só pode trocá-la (e ver quem é). O
                        // aluno inativo (só de leitura) também pode trocar a senha.
                        .requestMatchers(HttpMethod.POST, "/api/auth/trocar-senha").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/me").authenticated()
                        .anyRequest().access(AuthorizationManagers.allOf(
                                AuthenticatedAuthorizationManager.authenticated(),
                                AuthorizationManagers.not(
                                        AuthorityAuthorizationManager.hasAuthority(SENHA_TEMPORARIA)),
                                SO_CONSULTA_SE_SOMENTE_LEITURA)))
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(erros -> erros
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // A API só devolve JSON: nada nela carrega scripts, imagens ou frames. Se um
                // dia uma resposta for aberta direto no navegador, ela não executa nem embute nada.
                .headers(cabecalhos -> cabecalhos
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(permissoes -> permissoes
                                .policy("camera=(), microphone=(), geolocation=(), payment=()")))
                // Limite por usuário: depois do token validado, antes de chegar à rota.
                .addFilterAfter(new FiltroDeLimiteDeRequisicoes(limiteDeRequisicoes, resolver),
                        BearerTokenAuthenticationFilter.class)
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
        // O padrão do Spring escolhe o status e o WWW-Authenticate (RFC 6750). O cabeçalho é
        // reescrito só com o código do erro: a descrição trazia detalhes internos ("Signed JWT
        // rejected: Invalid signature", "Token revogado") que ajudam quem tenta forjar um token.
        BearerTokenAuthenticationEntryPoint padrao = new BearerTokenAuthenticationEntryPoint();
        return (request, response, excecao) -> {
            padrao.commence(request, response, excecao);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    excecao instanceof OAuth2AuthenticationException oauth
                            ? "Bearer error=\"" + oauth.getError().getErrorCode() + "\""
                            : "Bearer");
            resolver.resolveException(request, response, null, excecao);
        };
    }

    /** 403: token válido, mas o perfil não tem permissão. */
    @Bean
    AccessDeniedHandler accessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, excecao) -> resolver.resolveException(request, response, null, excecao);
    }

    /**
     * Valida assinatura, expiração ("exp", com 60 s de tolerância), emissor ("iss") e,
     * no banco, se o token não foi revogado (usuário ativo e versão do token atual). A
     * expiração é conferida no mesmo relógio que emitiu o token.
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey chaveJwt, JwtProperties properties, UsuarioRepository usuarioRepository,
                          Clock relogio) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(chaveJwt)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator expiracao = new JwtTimestampValidator();
        expiracao.setClock(relogio);
        // Os padrões do Spring continuam; o de expiração passado aqui toma o lugar do dele.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithValidators(expiracao, new JwtIssuerValidator(properties.emissor())),
                new TokenRevogadoValidator(usuarioRepository)));
        return decoder;
    }

    /**
     * Claim "perfil": "ADMIN" vira a autoridade ROLE_ADMIN, que é o que hasRole("ADMIN") procura.
     * Com senha temporária o token não ganha o perfil, só SENHA_TEMPORARIA: assim nenhuma
     * regra de perfil (@PreAuthorize) deixa passar, nem as que forem criadas no futuro.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter perfis = new JwtGrantedAuthoritiesConverter();
        perfis.setAuthoritiesClaimName(ClaimsDoToken.PERFIL);
        perfis.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (Boolean.TRUE.equals(jwt.getClaimAsBoolean(ClaimsDoToken.TROCAR_SENHA))) {
                return List.of(new SimpleGrantedAuthority(SENHA_TEMPORARIA));
            }
            List<GrantedAuthority> autoridades = new ArrayList<>(perfis.convert(jwt));
            if (Boolean.TRUE.equals(jwt.getClaimAsBoolean(ClaimsDoToken.SOMENTE_LEITURA))) {
                autoridades.add(new SimpleGrantedAuthority(SOMENTE_LEITURA));
            }
            return autoridades;
        });
        return converter;
    }
}
