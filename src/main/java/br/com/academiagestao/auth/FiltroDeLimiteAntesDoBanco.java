package br.com.academiagestao.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Recusa (429) quem já está sem ficha antes de o token ser conferido no banco (diagnóstico de
 * 18/10, S4): a validação completa consulta o usuário a cada requisição, e um token roubado em
 * laço gerava uma consulta por requisição mesmo recebendo 429.
 *
 * Aqui o token passa só pela assinatura, pela expiração e pelo emissor, sem banco: o "sub" já é
 * de confiança (só quem tem a chave assina). Só confere, sem gastar: quem gasta é o
 * {@link FiltroDeLimiteDeRequisicoes}, depois da validação completa. Assim um token revogado (que
 * ainda tem a assinatura certa) recebe 401 sem esgotar o balde do dono da conta. Token inválido
 * segue adiante, e o filtro do Spring dá o 401.
 *
 * Criado pela SegurancaConfig, e não como @Component (ver FiltroDeLimiteDeRequisicoes).
 */
class FiltroDeLimiteAntesDoBanco extends OncePerRequestFilter {

    private final JwtDecoder semBanco;
    private final LimiteDeRequisicoes limite;
    private final HandlerExceptionResolver resolver;

    FiltroDeLimiteAntesDoBanco(JwtDecoder semBanco, LimiteDeRequisicoes limite, HandlerExceptionResolver resolver) {
        this.semBanco = semBanco;
        this.limite = limite;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        String usuario = usuarioDoToken(requisicao);
        if (usuario != null && FiltroDeLimiteDeRequisicoes.recusou(limite.conferir(usuario), usuario, requisicao,
                resposta, resolver)) {
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }

    private String usuarioDoToken(HttpServletRequest requisicao) {
        try {
            String token = SegurancaConfig.tokenForaDasRotasDeSessao(requisicao);
            return token == null ? null : semBanco.decode(token).getSubject();
        } catch (OAuth2AuthenticationException | JwtException e) {
            return null;
        }
    }
}
