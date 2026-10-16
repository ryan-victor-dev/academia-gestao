package br.com.academiagestao.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Janela deslizante em memória: por IP (todas as tentativas) e por conta (só as falhas).
 * Basta para uma instância da aplicação; com várias, o contador precisaria ir para um
 * lugar compartilhado (banco ou Redis).
 */
@Component
class LimitadorDeTentativas {

    // Acima disso, as entradas que já esvaziaram são removidas, para o mapa não crescer sem fim.
    private static final int LIMPAR_ACIMA_DE = 10_000;

    private final LimitesDeLoginProperties limites;
    private final Clock relogio;
    private final Map<String, Deque<Instant>> tentativasPorIp = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> falhasPorConta = new ConcurrentHashMap<>();

    @Autowired
    LimitadorDeTentativas(LimitesDeLoginProperties limites) {
        this(limites, Clock.systemUTC());
    }

    LimitadorDeTentativas(LimitesDeLoginProperties limites, Clock relogio) {
        this.limites = limites;
        this.relogio = relogio;
    }

    /**
     * Registra a tentativa do IP e recusa se o IP ou a conta passaram do limite. Chamado
     * antes de conferir a senha: tentativa bloqueada não gasta o tempo do BCrypt.
     */
    void verificarERegistrar(String ip, String conta) {
        Instant agora = relogio.instant();
        limparSeCresceuDemais(agora);

        // Confere os dois limites antes de recusar, para o Retry-After dizer a maior espera:
        // se só o do IP fosse informado, o cliente voltaria e esbarraria no da conta.
        Duration esperarConta = Duration.ZERO;
        Deque<Instant> daConta = falhasPorConta.get(conta);
        if (daConta != null) {
            synchronized (daConta) {
                descartarAntigas(daConta, agora, limites.janelaPorConta());
                if (daConta.size() >= limites.falhasPorConta()) {
                    esperarConta = restante(daConta, agora, limites.janelaPorConta());
                }
            }
        }

        Deque<Instant> doIp = tentativasPorIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (doIp) {
            descartarAntigas(doIp, agora, limites.janelaPorIp());
            Duration esperarIp = doIp.size() >= limites.tentativasPorIp()
                    ? restante(doIp, agora, limites.janelaPorIp())
                    : Duration.ZERO;
            Duration esperar = esperarIp.compareTo(esperarConta) > 0 ? esperarIp : esperarConta;
            if (!esperar.isZero()) {
                throw new MuitasTentativasException(esperar);
            }
            doIp.addLast(agora);
        }
    }

    void registrarFalha(String conta) {
        Deque<Instant> daConta = falhasPorConta.computeIfAbsent(conta, k -> new ArrayDeque<>());
        synchronized (daConta) {
            daConta.addLast(relogio.instant());
        }
    }

    /** Login certo zera as falhas da conta. */
    void registrarSucesso(String conta) {
        falhasPorConta.remove(conta);
    }

    private static void descartarAntigas(Deque<Instant> fila, Instant agora, Duration janela) {
        Instant limite = agora.minus(janela);
        while (!fila.isEmpty() && !fila.peekFirst().isAfter(limite)) {
            fila.pollFirst();
        }
    }

    // Quanto falta para a tentativa mais antiga sair da janela (e liberar uma vaga).
    private static Duration restante(Deque<Instant> fila, Instant agora, Duration janela) {
        Duration falta = Duration.between(agora, fila.peekFirst().plus(janela));
        return falta.isNegative() ? Duration.ZERO : falta;
    }

    private void limparSeCresceuDemais(Instant agora) {
        if (tentativasPorIp.size() > LIMPAR_ACIMA_DE) {
            limpar(tentativasPorIp, agora, limites.janelaPorIp());
        }
        if (falhasPorConta.size() > LIMPAR_ACIMA_DE) {
            limpar(falhasPorConta, agora, limites.janelaPorConta());
        }
    }

    private static void limpar(Map<String, Deque<Instant>> mapa, Instant agora, Duration janela) {
        mapa.entrySet().removeIf(entrada -> {
            synchronized (entrada.getValue()) {
                descartarAntigas(entrada.getValue(), agora, janela);
                return entrada.getValue().isEmpty();
            }
        });
    }

    int tamanhoDosRegistros() {
        return tentativasPorIp.size() + falhasPorConta.size();
    }
}
