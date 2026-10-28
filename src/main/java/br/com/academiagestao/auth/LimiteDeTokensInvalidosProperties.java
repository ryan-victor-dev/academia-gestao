package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limite de tokens inválidos por IP (ver LimiteDeTokensInvalidos).
 *
 * @param porMinuto quantas requisições com token recusado (401) um IP faz por minuto; também é a
 *                  rajada aceita. 60 fica muito acima de quem usa o sistema de verdade (um token
 *                  vencido de vez em quando, que a tela renova) e muito abaixo de um laço.
 */
@ConfigurationProperties(prefix = "academia.tokens-invalidos")
record LimiteDeTokensInvalidosProperties(@DefaultValue("60") int porMinuto) {
}
