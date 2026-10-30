package br.com.academiagestao.compartilhado;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Recusa corpos de requisição grandes demais antes de qualquer outra coisa (inclusive do
 * login, que não exige autenticação). Sem isso, alguém mandando vários corpos de 15 MB
 * ao mesmo tempo esgotaria a memória da aplicação.
 *
 * Confere o Content-Length declarado e, como o cliente pode mandar o corpo em partes sem
 * declará-lo, também conta os bytes conforme são lidos.
 *
 * Uma exceção: o envio de anexo de uma publicação (ADR 0047), com o arquivo cru no corpo, vai até o maior anexo
 * aceito (o vídeo de 50 MB). Ele é lido aos pedaços de 1 MB, nunca inteiro na memória, e só depois de conferido o
 * perfil (o PerfilAntesDoCorpo barra quem não pode enviar antes de ler o corpo).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LimiteDeTamanhoDoCorpo extends OncePerRequestFilter {

    private static final Pattern ENVIO_DE_ANEXO = Pattern.compile("/api/publicacoes/[^/]+/anexos");

    private final long limite;
    private final long limiteDoAnexo;

    LimiteDeTamanhoDoCorpo(@Value("${academia.http.tamanho-maximo-corpo:64KB}") DataSize limite,
                           @Value("${academia.http.tamanho-maximo-anexo:50MB}") DataSize limiteDoAnexo) {
        this.limite = limite.toBytes();
        this.limiteDoAnexo = limiteDoAnexo.toBytes();
    }

    private long limiteDe(HttpServletRequest requisicao) {
        return "POST".equals(requisicao.getMethod()) && ENVIO_DE_ANEXO.matcher(requisicao.getRequestURI()).matches()
                ? limiteDoAnexo : limite;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        long limite = limiteDe(requisicao);
        if (requisicao.getContentLengthLong() > limite) {
            resposta.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            resposta.setContentType("application/problem+json");
            resposta.setCharacterEncoding("UTF-8");
            resposta.getWriter().write("""
                    {"type":"about:blank","title":"Corpo grande demais","status":413,\
                    "detail":"%s","instance":"%s"}""".formatted(
                    new CorpoGrandeDemaisException(limite).getMessage(),
                    requisicao.getRequestURI().replace("\\", "\\\\").replace("\"", "\\\"")));
            return;
        }
        cadeia.doFilter(new RequisicaoComLimite(requisicao, limite), resposta);
    }

    private static final class RequisicaoComLimite extends HttpServletRequestWrapper {

        private final long limite;
        private ServletInputStream entrada;

        RequisicaoComLimite(HttpServletRequest requisicao, long limite) {
            super(requisicao);
            this.limite = limite;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (entrada == null) {
                entrada = new EntradaContada(super.getInputStream(), limite);
            }
            return entrada;
        }
    }

    private static final class EntradaContada extends ServletInputStream {

        private final ServletInputStream original;
        private final long limite;
        private long lidos;

        EntradaContada(ServletInputStream original, long limite) {
            this.original = original;
            this.limite = limite;
        }

        private void contar(long quantidade) throws CorpoGrandeDemaisException {
            if (quantidade > 0) {
                lidos += quantidade;
                if (lidos > limite) {
                    throw new CorpoGrandeDemaisException(limite);
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = original.read();
            if (b != -1) {
                contar(1);
            }
            return b;
        }

        @Override
        public int read(byte[] destino, int inicio, int tamanho) throws IOException {
            int n = original.read(destino, inicio, tamanho);
            contar(n);
            return n;
        }

        @Override public boolean isFinished() { return original.isFinished(); }
        @Override public boolean isReady() { return original.isReady(); }
        @Override public void setReadListener(ReadListener ouvinte) { original.setReadListener(ouvinte); }
    }
}
