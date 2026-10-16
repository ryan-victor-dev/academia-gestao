package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Limites contra força bruta no login.
 *
 * @param tentativasPorIp quantas tentativas (certas ou erradas) um IP pode fazer na janela
 * @param janelaPorIp     tamanho dessa janela
 * @param falhasPorConta  quantas senhas erradas um e-mail aceita antes de ser bloqueado
 * @param janelaPorConta  por quanto tempo as falhas contam (e o bloqueio dura)
 */
@ConfigurationProperties(prefix = "academia.login")
record LimitesDeLoginProperties(
        @DefaultValue("20") int tentativasPorIp,
        @DefaultValue("1m") Duration janelaPorIp,
        @DefaultValue("10") int falhasPorConta,
        @DefaultValue("15m") Duration janelaPorConta) {
}
