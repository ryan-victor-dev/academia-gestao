package br.com.academiagestao.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Aplica o LimiteDeRequisicoes logo depois de o token ser validado, quando já se sabe quem é
 * o usuário (o "sub" do token, o id dele). Sem login (o próprio login, o health) não conta aqui: o login tem os seus
 * limites. Passou do limite: 429 com Retry-After, pelo mesmo tratador de erros da API.
 *
 * Criado pela SegurancaConfig, e não como @Component: um filtro registrado como bean também
 * entraria sozinho na cadeia do servidor, fora da segurança, e rodaria duas vezes.
 */
class FiltroDeLimiteDeRequisicoes extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroDeLimiteDeRequisicoes.class);

    private final LimiteDeRequisicoes limite;
    private final HandlerExceptionResolver resolver;

    FiltroDeLimiteDeRequisicoes(LimiteDeRequisicoes limite, HandlerExceptionResolver resolver) {
        this.limite = limite;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        // Só quem entrou com token. Anônimos (login, health) ficariam todos num balde só.
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken autenticacao) {
            LimiteDeRequisicoes.Resultado resultado = limite.consumir(autenticacao.getName());
            if (!resultado.liberada()) {
                if (resultado.avisar()) {
                    log.warn("Limite de requisições atingido: usuário {} / {} {}", autenticacao.getName(),
                            requisicao.getMethod(), requisicao.getRequestURI());
                }
                resolver.resolveException(requisicao, resposta, null,
                        new MuitasRequisicoesException(resultado.esperar()));
                return;
            }
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
