package br.com.academiagestao.compartilhado;

import org.junit.jupiter.api.Test;

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
}
