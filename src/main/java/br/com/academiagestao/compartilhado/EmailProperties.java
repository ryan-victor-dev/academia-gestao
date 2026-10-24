package br.com.academiagestao.compartilhado;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * O servidor de e-mail (ADR 0038), no {@code .env}. Sem {@code host}, o e-mail fica desligado.
 *
 * @param host       o servidor SMTP (vazio: desligado)
 * @param porta      587 é a do envio com STARTTLS, a mais comum
 * @param usuario    vazio: o servidor não pede login (o Mailpit dos testes, por exemplo)
 * @param tls        STARTTLS obrigatório; só o Mailpit dos testes, na mesma máquina, dispensa
 * @param remetente  o endereço que aparece como "de"
 * @param espera     o teto de cada etapa da conversa com o servidor (conectar, ler, escrever)
 */
@ConfigurationProperties(prefix = "academia.email")
public record EmailProperties(
        String host,
        @DefaultValue("587") int porta,
        String usuario,
        String senha,
        @DefaultValue("true") boolean tls,
        String remetente,
        @DefaultValue("10s") Duration espera) {
}
