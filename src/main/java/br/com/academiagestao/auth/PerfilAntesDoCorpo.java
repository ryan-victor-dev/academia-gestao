package br.com.academiagestao.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Confere o perfil do {@code @PreAuthorize} antes de o corpo da requisição ser lido. O Spring
 * valida o corpo ({@code @Valid}) ao montar os argumentos, e o {@code @PreAuthorize} só é
 * avaliado na chamada do método: quem não tinha o perfil recebia 400 com as regras dos campos,
 * e não 403 (diagnóstico de 2026-10-18, S2; etapa 6, S5). A conferência na chamada continua,
 * como segunda barreira. As regras do projeto só usam o perfil (hasRole, hasAnyRole), nunca os
 * argumentos, que aqui ainda não existem.
 */
@Configuration
@ConditionalOnWebApplication
class PerfilAntesDoCorpo implements WebMvcConfigurer, HandlerInterceptor {

    private final PreAuthorizeAuthorizationManager regras = new PreAuthorizeAuthorizationManager();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod metodo && metodo.hasMethodAnnotation(PreAuthorize.class)) {
            AuthorizationResult resultado = regras.authorize(SecurityContextHolder.getContext()::getAuthentication,
                    new SimpleMethodInvocation(metodo.getBean(), metodo.getMethod()));
            if (resultado != null && !resultado.isGranted()) {
                throw new AccessDeniedException("Perfil sem permissão para esta rota");
            }
        }
        return true;
    }
}
