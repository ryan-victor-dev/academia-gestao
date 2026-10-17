package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limite das rotas autenticadas, por usuário (ver LimiteDeRequisicoes).
 *
 * @param porMinuto quantas requisições um usuário faz por minuto; também é o tamanho da
 *                  rajada aceita de uma vez. 300 = 5 por segundo sem parar, bem acima do uso
 *                  de uma recepção (decisão do dono, revisão de 2026-10-17).
 */
@ConfigurationProperties(prefix = "academia.requisicoes")
record LimiteDeRequisicoesProperties(@DefaultValue("300") int porMinuto) {
}
