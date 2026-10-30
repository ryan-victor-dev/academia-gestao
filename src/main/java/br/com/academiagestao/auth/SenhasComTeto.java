package br.com.academiagestao.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * O BCrypt com um teto de contas ao mesmo tempo (ADR 0046, item 3). Ele é lento de propósito, e cada conta
 * ocupa um núcleo: uma enxurrada de logins, de muitos IPs (o limite por IP não segura), tomava a CPU,
 * e todo o resto do sistema ficava lento (de 986 para 47 requisições por segundo). Com o teto, quem
 * passa dele espera um pouco por uma vaga e, sem vaga, recebe 503 com Retry-After: os logins de mais
 * ficam de fora, e o resto continua com CPU.
 *
 * Vale para toda conta de senha (login, troca, esqueci a senha, primeiro acesso, senha temporária):
 * qualquer uma dessas rotas serviria para a mesma enxurrada.
 */
final class SenhasComTeto implements PasswordEncoder {

    private static final Logger log = LoggerFactory.getLogger(SenhasComTeto.class);
    private static final Duration ENTRE_AVISOS = Duration.ofMinutes(1);

    private final PasswordEncoder bcrypt;
    private final Semaphore vagas;
    private final int teto;
    private final Duration espera;
    private final Clock relogio;
    private final AtomicReference<Instant> ultimoAviso = new AtomicReference<>(Instant.MIN);

    SenhasComTeto(PasswordEncoder bcrypt, int teto, Duration espera, Clock relogio) {
        this.bcrypt = bcrypt;
        this.vagas = new Semaphore(teto);
        this.teto = teto;
        this.espera = espera;
        this.relogio = relogio;
    }

    @Override
    public String encode(CharSequence senha) {
        return comVaga(() -> bcrypt.encode(senha));
    }

    @Override
    public boolean matches(CharSequence senha, String hash) {
        return comVaga(() -> bcrypt.matches(senha, hash));
    }

    @Override
    public boolean upgradeEncoding(String hash) {
        return bcrypt.upgradeEncoding(hash);
    }

    private <T> T comVaga(Supplier<T> conta) {
        if (!entrar()) {
            avisar();
            throw new MuitasSenhasAoMesmoTempoException();
        }
        try {
            return conta.get();
        } finally {
            vagas.release();
        }
    }

    private boolean entrar() {
        try {
            return vagas.tryAcquire(espera.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** No máximo um aviso por minuto: numa enxurrada, um por recusa encheria o log. */
    private void avisar() {
        Instant agora = relogio.instant();
        Instant anterior = ultimoAviso.get();
        if (agora.isAfter(anterior.plus(ENTRE_AVISOS)) && ultimoAviso.compareAndSet(anterior, agora)) {
            log.warn("Teto de senhas ao mesmo tempo atingido ({}): logins recusados com 503", teto);
        }
    }
}
