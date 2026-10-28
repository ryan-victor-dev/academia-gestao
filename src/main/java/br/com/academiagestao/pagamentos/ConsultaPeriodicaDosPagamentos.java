package br.com.academiagestao.pagamentos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * De tempos em tempos, pergunta à empresa pelos pagamentos online em aberto (ADR 0034): cobre
 * o aviso que se perdeu e o ambiente sem endereço público, onde o aviso nunca chega. Só com a
 * aplicação web (a recuperação de ADMIN sobe sem web e não consulta nada).
 */
@Component
@ConditionalOnWebApplication
class ConsultaPeriodicaDosPagamentos {

    private final ConfirmacaoDePagamentoOnlineService service;

    ConsultaPeriodicaDosPagamentos(ConfirmacaoDePagamentoOnlineService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${academia.pagamentos.intervalo-da-consulta:2m}",
            initialDelayString = "${academia.pagamentos.intervalo-da-consulta:2m}")
    void consultar() {
        service.consultarAguardando();
    }

    /** Os cancelados e expirados recentes (ADR 0036): pagos no último segundo, ou o cancelamento lá falhou. */
    @Scheduled(fixedDelayString = "${academia.pagamentos.intervalo-da-consulta-dos-encerrados:1h}",
            initialDelayString = "${academia.pagamentos.intervalo-da-consulta-dos-encerrados:1h}")
    void consultarEncerrados() {
        service.consultarEncerrados();
    }

    /** Os cartões recebidos nos últimos meses (ADR 0037): a contestação chega semanas depois. */
    @Scheduled(fixedDelayString = "${academia.pagamentos.intervalo-da-consulta-das-contestacoes:24h}",
            initialDelayString = "${academia.pagamentos.intervalo-da-consulta-das-contestacoes:24h}")
    void consultarContestacoes() {
        service.consultarContestacoes();
    }
}
