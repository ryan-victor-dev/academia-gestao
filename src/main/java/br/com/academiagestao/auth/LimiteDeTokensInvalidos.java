package br.com.academiagestao.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Limite de tokens inválidos por IP (diagnóstico de 27/10, B7; decisão do dono): um IP mandava
 * 1.480 tokens inválidos por segundo, todos 401, sem limite nenhum. Não chegava ao banco (a
 * assinatura é conferida antes), mas gastava CPU à vontade. Agora cada 401 de quem mandou um token
 * gasta uma ficha do IP, e o IP sem fichas recebe 429 antes de o token ser conferido.
 *
 * O mesmo balde de fichas do limite por usuário (LimiteDeRequisicoes), com outra chave e outro
 * tamanho, e as mesmas premissas: em memória, uma instância só (ADR 0042, B5). Não segura a
 * enxurrada de muitos IPs, que é outra decisão do dono (pendências, item 3).
 */
@Component
@ConditionalOnWebApplication
class LimiteDeTokensInvalidos {

    private final LimiteDeRequisicoes baldes;

    LimiteDeTokensInvalidos(LimiteDeTokensInvalidosProperties limites, Clock relogio) {
        if (limites.porMinuto() < 1) {
            throw new IllegalStateException("academia.tokens-invalidos.por-minuto precisa ser pelo menos 1");
        }
        this.baldes = new LimiteDeRequisicoes(new LimiteDeRequisicoesProperties(limites.porMinuto()), relogio);
    }

    /** Um token do IP foi recusado (401): gasta uma ficha. */
    void registrarRecusa(String ip) {
        baldes.consumir(ip);
    }

    /** Sem gastar: diz se o IP já está sem fichas (e quanto falta para a próxima). */
    LimiteDeRequisicoes.Resultado conferir(String ip) {
        return baldes.conferir(ip);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void limparCheios() {
        baldes.limparCheios();
    }

    int ipsAcompanhados() {
        return baldes.usuariosAcompanhados();
    }
}
