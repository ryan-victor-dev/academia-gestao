package br.com.academiagestao.pagamentos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Pagamento online (ADR 0034).
 *
 * @param provedor            {@code desligado} (padrão: o app não oferece pagar), {@code simulado}
 *                            (desenvolvimento e testes) ou o nome de uma empresa implementada
 * @param intervaloDaConsulta de quanto em quanto tempo perguntar à empresa pelos pagamentos em aberto
 * @param toleranciaDoPix     depois de expirado, por quanto tempo ainda perguntar (um pagamento no
 *                            último segundo pode demorar a aparecer)
 * @param toleranciaDoBoleto  o boleto pago no vencimento pode levar dias úteis para compensar
 * @param tokenDoAvisoSimulado só no simulado: o token que o aviso de teste precisa trazer
 */
@ConfigurationProperties(prefix = "academia.pagamentos")
record PagamentosProperties(
        @DefaultValue("desligado") String provedor,
        @DefaultValue("2m") Duration intervaloDaConsulta,
        @DefaultValue("1h") Duration toleranciaDoPix,
        @DefaultValue("5d") Duration toleranciaDoBoleto,
        String tokenDoAvisoSimulado) {

    static final String DESLIGADO = "desligado";
    static final String SIMULADO = "simulado";
}
