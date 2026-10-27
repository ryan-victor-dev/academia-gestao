package br.com.academiagestao.compartilhado;

import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.TomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Com {@code academia.proxy-confiavel} (o IP fixo do Caddy no Docker), só esse IP pode mandar
 * X-Forwarded-For e X-Forwarded-Proto. Sem ele, o Tomcat confia em qualquer IP interno (10/8,
 * 172.16/12, 192.168/16, 127/8): de outro container ou da própria máquina, bastaria trocar o
 * cabeçalho a cada tentativa para furar o limite de login por IP e falsificar o IP no registro
 * de acessos. Vazio (rodando pelo Maven, testes), fica o padrão do Tomcat.
 */
@Component
@ConditionalOnWebApplication
class ProxyConfiavel implements WebServerFactoryCustomizer<TomcatWebServerFactory> {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final String ip;

    ProxyConfiavel(@Value("${academia.proxy-confiavel:}") String ip) {
        this.ip = ip.strip();
        if (!this.ip.isEmpty() && !IPV4.matcher(this.ip).matches()) {
            throw new IllegalStateException("academia.proxy-confiavel precisa ser um IPv4, como 10.250.250.10");
        }
    }

    // Roda depois do customizador do Spring Boot (ordem 0), que é quem cria a válvula.
    @Override
    public void customize(TomcatWebServerFactory fabrica) {
        if (ip.isEmpty()) {
            return;
        }
        RemoteIpValve valvula = fabrica.getEngineValves().stream()
                .filter(RemoteIpValve.class::isInstance).map(RemoteIpValve.class::cast).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "academia.proxy-confiavel exige server.forward-headers-strategy=native"));
        valvula.setInternalProxies(Pattern.quote(ip));
        valvula.setTrustedProxies(null);
    }
}
