package br.com.academiagestao.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Recusa (429) quem traz um token de um IP que já gastou as fichas de tokens inválidos
 * (LimiteDeTokensInvalidos), antes de o token ser conferido: é a conferência que custa. Quem gasta
 * a ficha é o ponto de entrada dos 401 (SegurancaConfig). Requisição sem token passa: não há o
 * que conferir, e o login tem o limite dele.
 *
 * Criado pela SegurancaConfig, e não como @Component (ver FiltroDeLimiteDeRequisicoes).
 */
class FiltroDeTokensInvalidos extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroDeTokensInvalidos.class);

    private final LimiteDeTokensInvalidos limite;
    private final HandlerExceptionResolver resolver;

    FiltroDeTokensInvalidos(LimiteDeTokensInvalidos limite, HandlerExceptionResolver resolver) {
        this.limite = limite;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        if (SegurancaConfig.tokenForaDasRotasDeSessao(requisicao) != null) {
            LimiteDeRequisicoes.Resultado resultado = limite.conferir(requisicao.getRemoteAddr());
            if (!resultado.liberada()) {
                if (resultado.avisar()) {
                    log.warn("Tokens inválidos demais: IP {} / {} {}", requisicao.getRemoteAddr(),
                            requisicao.getMethod(), requisicao.getRequestURI());
                }
                resolver.resolveException(requisicao, resposta, null, new MuitasRequisicoesException(resultado.esperar()));
                return;
            }
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
