package br.com.academiagestao.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de requisições por usuário logado (revisão de 2026-10-17): um token fez 1.081
 * requisições por segundo sem nenhum 429. Um token roubado, um script com defeito ou um
 * funcionário mal-intencionado degradavam o sistema para todos.
 *
 * Balde de fichas: cada usuário começa com {@code porMinuto} fichas, cada requisição gasta
 * uma, e elas voltam aos poucos ({@code porMinuto} por minuto). Aceita rajadas curtas (abrir
 * uma tela que faz várias chamadas) e barra o uso contínuo acima do limite.
 *
 * Em vez de contar fichas (em ponto flutuante, com arredondamentos), cada balde guarda só o
 * momento em que estaria cheio de novo: cada requisição empurra esse momento um intervalo
 * (1 min / porMinuto) para a frente, e ela só passa se ele não ficar mais de 1 minuto à
 * frente de agora. Conta exata, em nanossegundos.
 *
 * Em memória, e não no banco como o limite de login: gravar no banco a cada requisição
 * custaria uma conexão por chamada, justamente o recurso que o limite protege. Com mais de
 * uma instância da aplicação, cada uma contaria o seu (o limite efetivo se multiplicaria).
 */
// Só com servidor web, como a SegurancaConfig que o usa (e registra as propriedades dele):
// o comando de recuperação de ADMIN sobe sem web (ModoComandoTest).
@Component
@ConditionalOnWebApplication
class LimiteDeRequisicoes {

    private static final Duration JANELA = Duration.ofMinutes(1);

    private final Duration intervalo;
    private final Duration janela;
    private final Clock relogio;
    private final ConcurrentHashMap<String, Balde> baldes = new ConcurrentHashMap<>();

    LimiteDeRequisicoes(LimiteDeRequisicoesProperties limites, Clock relogio) {
        if (limites.porMinuto() < 1) {
            throw new IllegalStateException("academia.requisicoes.por-minuto precisa ser pelo menos 1");
        }
        this.intervalo = JANELA.dividedBy(limites.porMinuto());
        this.janela = intervalo.multipliedBy(limites.porMinuto());
        this.relogio = relogio;
    }

    /**
     * Gasta uma ficha do usuário. Sem ficha, diz quanto falta para a próxima (a requisição
     * recusada não gasta nada) e se o log deve avisar: no máximo uma vez por minuto por
     * usuário, e não a cada requisição de quem insiste (o log tem tamanho limitado, e um
     * aviso por recusa empurraria o resto para fora).
     */
    Resultado consumir(String usuario) {
        Instant agora = relogio.instant();
        Resultado[] resultado = {Resultado.LIBERADA};
        // compute é atômico por chave: duas requisições do mesmo usuário não gastam a mesma ficha.
        baldes.compute(usuario, (chave, balde) -> {
            // Mais de uma janela à frente só acontece se o relógio voltou (um ajuste do NTP, o
            // relógio dos testes restaurado): o balde recomeça cheio, em vez de prender o
            // usuário até a hora antiga voltar. Ganha-se no máximo uma janela, e só nesse caso.
            Instant cheioEm = balde == null || balde.cheioEm().isBefore(agora)
                    || balde.cheioEm().isAfter(agora.plus(janela)) ? agora : balde.cheioEm();
            Instant novoCheioEm = cheioEm.plus(intervalo);
            Duration excesso = Duration.between(agora, novoCheioEm).minus(janela);
            if (excesso.isNegative() || excesso.isZero()) {
                return new Balde(novoCheioEm, balde == null ? null : balde.avisadoEm());
            }
            // Aviso no futuro: o relógio voltou, e o log não deve ficar calado até a hora antiga.
            boolean avisar = balde.avisadoEm() == null || balde.avisadoEm().isAfter(agora)
                    || !agora.isBefore(balde.avisadoEm().plus(JANELA));
            resultado[0] = new Resultado(excesso, avisar);
            return new Balde(balde.cheioEm(), avisar ? agora : balde.avisadoEm());
        });
        return resultado[0];
    }

    record Resultado(Duration esperar, boolean avisar) {
        static final Resultado LIBERADA = new Resultado(Duration.ZERO, false);

        boolean liberada() {
            return esperar.isZero();
        }
    }

    /** Balde já cheio é igual a não ter balde: sai da memória. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void limparCheios() {
        Instant agora = relogio.instant();
        baldes.values().removeIf(balde -> !balde.cheioEm().isAfter(agora));
    }

    int usuariosAcompanhados() {
        return baldes.size();
    }

    private record Balde(Instant cheioEm, Instant avisadoEm) {
    }
}
