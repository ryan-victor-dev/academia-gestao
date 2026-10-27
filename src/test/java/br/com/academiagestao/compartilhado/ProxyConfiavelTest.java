package br.com.academiagestao.compartilhado;

import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Um valor errado no .env impede a subida, em vez de deixar o limite por IP aberto ou fechado. */
class ProxyConfiavelTest {

    @Test
    void recusaOQueNaoEhUmIpv4() {
        assertThatThrownBy(() -> new ProxyConfiavel("caddy")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProxyConfiavel("10.250.250.*")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aceitaIpv4OuVazio() {
        assertThatCode(() -> new ProxyConfiavel(" 10.250.250.10 ")).doesNotThrowAnyException();
        assertThatCode(() -> new ProxyConfiavel("")).doesNotThrowAnyException();
    }

    /** Com o IP, só ele é proxy de confiança; sem o IP, a válvula do Spring fica como veio. */
    @Test
    void soOIpDoCaddyEhProxyDeConfianca() {
        TomcatServletWebServerFactory fabrica = new TomcatServletWebServerFactory();
        RemoteIpValve valvula = new RemoteIpValve();
        String padrao = valvula.getInternalProxies();
        valvula.setTrustedProxies("10\\..*");
        fabrica.addEngineValves(valvula);

        new ProxyConfiavel("").customize(fabrica);
        assertThat(valvula.getInternalProxies()).isEqualTo(padrao);
        assertThat(valvula.getTrustedProxies()).isEqualTo("10\\..*");

        new ProxyConfiavel("10.250.250.10").customize(fabrica);
        assertThat(valvula.getInternalProxies()).isEqualTo(Pattern.quote("10.250.250.10"));
        assertThat(valvula.getTrustedProxies()).isNull();
    }

    @Test
    void semAValvulaNaoSobe() {
        assertThatThrownBy(() -> new ProxyConfiavel("10.250.250.10").customize(new TomcatServletWebServerFactory()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forward-headers-strategy=native");
    }
}
