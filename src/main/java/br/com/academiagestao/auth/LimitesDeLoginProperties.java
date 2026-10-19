package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Limites contra força bruta no login.
 *
 * @param tentativasPorIp       quantas tentativas (certas ou erradas) um IP pode fazer na janela
 * @param janelaPorIp           tamanho dessa janela
 * @param falhasPorContaEIp     quantas senhas erradas uma conta aceita vindas de um mesmo IP antes
 *                              de bloquear esse IP nessa conta (os outros IPs continuam entrando)
 * @param falhasPorContaNoTotal quantas senhas erradas uma conta aceita somando todos os IPs antes
 *                              de ser bloqueada para todos: o teto contra o ataque distribuído
 * @param janelaPorConta        por quanto tempo as falhas contam (e o bloqueio dura)
 */
@ConfigurationProperties(prefix = "academia.login")
record LimitesDeLoginProperties(
        @DefaultValue("20") int tentativasPorIp,
        @DefaultValue("1m") Duration janelaPorIp,
        @DefaultValue("10") int falhasPorContaEIp,
        @DefaultValue("100") int falhasPorContaNoTotal,
        @DefaultValue("15m") Duration janelaPorConta) {
}
