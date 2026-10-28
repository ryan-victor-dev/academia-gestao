package br.com.academiagestao.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Os códigos de 6 dígitos mandados por e-mail (V50, ADR 0038): confirmar o e-mail, recuperar a
 * senha e o primeiro acesso do aluno (V51, ADR 0039). Valem 15 minutos, uma vez só, com até 5 tentativas; o novo encerra o anterior; no
 * máximo 5 por hora e 10 em 24 horas (ADR 0041, M3: sem o teto diário, eram ~600 chutes por dia contra
 * uma conta), com 1 minuto entre um e outro. Só o hash fica no banco.
 */
@Component
class CodigosDeEmail {

    /** O dono do código: o login ou, no primeiro acesso, o aluno (o login ainda não existe). */
    enum Finalidade {
        CONFIRMACAO("usuario_id"), RECUPERACAO("usuario_id"), PRIMEIRO_ACESSO("aluno_id");

        // Nome de coluna fixo, daqui: vai no texto do SQL.
        private final String dono;

        Finalidade(String dono) {
            this.dono = dono;
        }
    }

    enum Resultado { CONFERE, NAO_CONFERE }

    static final Duration VALIDADE = Duration.ofMinutes(15);
    static final int TENTATIVAS = 5;
    static final int POR_HORA = 5;
    static final int POR_DIA = 10;
    static final Duration INTERVALO = Duration.ofMinutes(1);

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock relogio;

    CodigosDeEmail(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock relogio) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.relogio = relogio;
    }

    /**
     * Um código novo para o e-mail do dono (o anterior para de valer). Na transação de quem
     * chama; o envio do e-mail vem depois, fora dela.
     *
     * @return o código, para o e-mail (nunca vai para o banco nem para o log)
     * @throws MuitosCodigosException pediu demais em pouco tempo
     */
    @Transactional(propagation = Propagation.MANDATORY)
    String emitir(UUID dono, Finalidade finalidade, String email) {
        Instant agora = relogio.instant();
        // Trava o dono: dois pedidos ao mesmo tempo contam um depois do outro (sem código nenhum
        // ainda, travar os códigos não travaria nada). O aluno é de outro módulo: em vez da linha
        // dele, uma trava do Postgres pelo id, solta no fim da transação.
        if (finalidade.dono.equals("usuario_id")) {
            jdbc.queryForObject("SELECT id FROM usuarios WHERE id = ? FOR UPDATE", UUID.class, dono);
        } else {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class,
                    "codigo-de-email:" + dono);
        }
        List<Instant> ultimoDia = jdbc.queryForList("""
                SELECT criado_em FROM codigos_de_email WHERE %s = ? AND finalidade = ? AND criado_em > ?
                ORDER BY criado_em DESC""".formatted(finalidade.dono), Timestamp.class, dono, finalidade.name(),
                Timestamp.from(agora.minus(Duration.ofDays(1)))).stream().map(Timestamp::toInstant).toList();
        List<Instant> ultimaHora = ultimoDia.stream().filter(t -> t.isAfter(agora.minus(Duration.ofHours(1)))).toList();
        if (!ultimoDia.isEmpty() && ultimoDia.get(0).plus(INTERVALO).isAfter(agora)) {
            throw new MuitosCodigosException(Duration.between(agora, ultimoDia.get(0).plus(INTERVALO)));
        }
        // Os dois tetos de uma vez: a espera é até o fim do que demora mais.
        Duration esperar = Duration.ZERO;
        if (ultimaHora.size() >= POR_HORA) {
            esperar = Duration.between(agora, ultimaHora.getLast().plus(Duration.ofHours(1)));
        }
        if (ultimoDia.size() >= POR_DIA) {
            Duration ateODia = Duration.between(agora, ultimoDia.getLast().plus(Duration.ofDays(1)));
            esperar = ateODia.compareTo(esperar) > 0 ? ateODia : esperar;
        }
        if (esperar.isPositive()) {
            throw new MuitosCodigosException(esperar);
        }
        jdbc.update("UPDATE codigos_de_email SET encerrado_em = ? WHERE " + finalidade.dono + " = ? AND finalidade = ? "
                + "AND encerrado_em IS NULL", Timestamp.from(agora), dono, finalidade.name());
        String codigo = String.format("%06d", ALEATORIO.nextInt(1_000_000));
        jdbc.update("""
                INSERT INTO codigos_de_email (id, %s, finalidade, email, codigo_hash, criado_em, expira_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)""".formatted(finalidade.dono), UUID.randomUUID(), dono, finalidade.name(), email,
                passwordEncoder.encode(codigo), Timestamp.from(agora), Timestamp.from(agora.plus(VALIDADE)));
        return codigo;
    }

    /**
     * Confere o código do dono para o e-mail dado, e gasta uma tentativa. Em transação
     * própria: a tentativa errada fica gravada mesmo que quem chamou desfaça a dele. Confere:
     * o código acaba (uso único).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Resultado conferir(UUID dono, Finalidade finalidade, String email, String codigo) {
        Instant agora = relogio.instant();
        List<Map<String, Object>> abertos = jdbc.queryForList("""
                SELECT id, email, codigo_hash, expira_em, tentativas FROM codigos_de_email
                WHERE %s = ? AND finalidade = ? AND encerrado_em IS NULL FOR UPDATE""".formatted(finalidade.dono),
                dono, finalidade.name());
        if (abertos.isEmpty()) {
            return Resultado.NAO_CONFERE;
        }
        Map<String, Object> aberto = abertos.get(0);
        UUID id = (UUID) aberto.get("id");
        int tentativas = (Integer) aberto.get("tentativas") + 1;
        boolean vencido = !((Timestamp) aberto.get("expira_em")).toInstant().isAfter(agora);
        boolean confere = !vencido && email.equals(aberto.get("email")) && codigo != null && codigo.matches("\\d{6}")
                && passwordEncoder.matches(codigo, (String) aberto.get("codigo_hash"));
        boolean encerra = confere || vencido || tentativas >= TENTATIVAS;
        jdbc.update("UPDATE codigos_de_email SET tentativas = ?, encerrado_em = ? WHERE id = ?", tentativas,
                encerra ? Timestamp.from(agora) : null, id);
        return confere ? Resultado.CONFERE : Resultado.NAO_CONFERE;
    }
}
