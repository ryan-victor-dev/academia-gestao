package br.com.academiagestao.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Janela deslizante no banco: por IP (todas as tentativas) e por conta (só as falhas).
 * As falhas de uma conta contam de dois jeitos: as vindas de um mesmo IP bloqueiam só esse
 * IP nessa conta, e a soma de todos os IPs, com um teto bem maior, bloqueia a conta inteira.
 * Assim, quem sabe só o e-mail não tranca o dono para fora (diagnóstico de 2026-10-18), e um
 * ataque espalhado por muitos IPs continua com um total de chutes limitado.
 * No banco, e não em memória, para os bloqueios sobreviverem a um reinício da aplicação
 * e valerem para todas as instâncias, se um dia houver mais de uma.
 *
 * Tudo roda em transação própria (REQUIRES_NEW): o login acontece dentro de uma transação
 * que é desfeita quando a senha está errada, e a falha registrada seria desfeita junto.
 */
@Component
class LimitadorDeTentativas {

    private static final String IP = "IP";
    private static final String CONTA = "CONTA";

    private final LimitesDeLoginProperties limites;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacaoPropria;
    private final Clock relogio;

    LimitadorDeTentativas(LimitesDeLoginProperties limites, JdbcTemplate jdbc,
                          PlatformTransactionManager transacoes, Clock relogio) {
        this.limites = limites;
        this.jdbc = jdbc;
        this.transacaoPropria = new TransactionTemplate(transacoes);
        this.transacaoPropria.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.relogio = relogio;
    }

    /**
     * Registra a tentativa do IP e recusa se o IP ou a conta passaram do limite. Chamado
     * antes de conferir a senha: tentativa bloqueada não gasta o tempo do BCrypt.
     */
    void verificarERegistrar(String ip, String conta) {
        Instant agora = relogio.instant();
        transacaoPropria.executeWithoutResult(status -> {
            // Trava as duas chaves até o fim da transação (sempre na mesma ordem: sem deadlock).
            // Duas tentativas simultâneas do mesmo IP não passam juntas pela contagem.
            travar(CONTA, conta);
            travar(IP, ip);

            // Os dois limites são conferidos antes de recusar, para o Retry-After dizer a
            // maior espera: se só o do IP fosse informado, o cliente esbarraria no da conta.
            Duration esperar = maior(
                    espera(CONTA, conta, ip, limites.falhasPorContaEIp(), limites.janelaPorConta(), agora),
                    espera(CONTA, conta, null, limites.falhasPorContaNoTotal(), limites.janelaPorConta(), agora),
                    espera(IP, ip, null, limites.tentativasPorIp(), limites.janelaPorIp(), agora));
            if (!esperar.isZero()) {
                throw new MuitasTentativasException(esperar);
            }
            registrar(IP, ip, null, agora);
        });
    }

    void registrarFalha(String conta, String ip) {
        Instant agora = relogio.instant();
        transacaoPropria.executeWithoutResult(status -> registrar(CONTA, conta, ip, agora));
    }

    /**
     * Login certo zera as falhas da conta vindas desse IP (quem errou a própria senha algumas
     * vezes não fica perto do bloqueio). As dos outros IPs ficam: se a senha certa do dono
     * zerasse tudo, o ataque espalhado ganharia um teto novo a cada vez que ele entrasse.
     */
    void registrarSucesso(String conta, String ip) {
        transacaoPropria.executeWithoutResult(status ->
                jdbc.update("DELETE FROM tentativas_login WHERE tipo = ? AND chave = ? AND ip = ?", CONTA, conta, ip));
    }

    /** Apaga o que já saiu de todas as janelas: os registros têm IP e e-mail (LGPD). */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void limparAntigos() {
        Duration maiorJanela = limites.janelaPorConta().compareTo(limites.janelaPorIp()) > 0
                ? limites.janelaPorConta() : limites.janelaPorIp();
        jdbc.update("DELETE FROM tentativas_login WHERE momento <= ?",
                Timestamp.from(relogio.instant().minus(maiorJanela)));
    }

    private void travar(String tipo, String chave) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tipo + ":" + chave);
    }

    private void registrar(String tipo, String chave, String ip, Instant momento) {
        jdbc.update("INSERT INTO tentativas_login (tipo, chave, ip, momento) VALUES (?, ?, ?, ?)",
                tipo, chave, ip, Timestamp.from(momento));
    }

    private static Duration maior(Duration... esperas) {
        Duration maior = Duration.ZERO;
        for (Duration espera : esperas) {
            if (espera.compareTo(maior) > 0) {
                maior = espera;
            }
        }
        return maior;
    }

    /**
     * Zero se ainda há vaga na janela; senão, quanto falta para a tentativa mais antiga
     * dentro do limite sair da janela e liberar uma vaga. Com {@code ip}, conta só as
     * tentativas vindas dele; sem, todas as da chave.
     */
    private Duration espera(String tipo, String chave, String ip, int limite, Duration janela, Instant agora) {
        Timestamp inicio = Timestamp.from(agora.minus(janela));
        var momentos = ip == null
                ? jdbc.queryForList("""
                        SELECT momento FROM tentativas_login
                        WHERE tipo = ? AND chave = ? AND momento > ?
                        ORDER BY momento DESC LIMIT ?""",
                Timestamp.class, tipo, chave, inicio, limite)
                : jdbc.queryForList("""
                        SELECT momento FROM tentativas_login
                        WHERE tipo = ? AND chave = ? AND ip = ? AND momento > ?
                        ORDER BY momento DESC LIMIT ?""",
                Timestamp.class, tipo, chave, ip, inicio, limite);
        if (momentos.size() < limite) {
            return Duration.ZERO;
        }
        Instant liberaEm = momentos.getLast().toInstant().plus(janela);
        Duration falta = Duration.between(agora, liberaEm);
        return falta.isNegative() ? Duration.ZERO : falta;
    }
}
