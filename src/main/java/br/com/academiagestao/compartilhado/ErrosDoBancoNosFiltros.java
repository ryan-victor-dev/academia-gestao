package br.com.academiagestao.compartilhado;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Banco sobrecarregado antes de chegar ao controller. A validação do token consulta o banco
 * dentro do filtro de segurança (usuário ativo, versão do token); com o pool esgotado, o erro
 * saía dali como 500, fora do alcance do ApiExceptionHandler. Aqui ele é repassado ao mesmo
 * tratador e vira o mesmo 503 com Retry-After. Fica logo depois do limite de tamanho do corpo,
 * antes da segurança. Só com servidor web: o comando de recuperação de ADMIN sobe sem ele.
 */
@Component
@ConditionalOnWebApplication
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class ErrosDoBancoNosFiltros extends OncePerRequestFilter {

    private final HandlerExceptionResolver resolver;

    ErrosDoBancoNosFiltros(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        try {
            cadeia.doFilter(requisicao, resposta);
        } catch (CannotCreateTransactionException | DataAccessResourceFailureException | QueryTimeoutException e) {
            if (resposta.isCommitted() || resolver.resolveException(requisicao, resposta, null, e) == null) {
                throw e;
            }
        }
    }
}
