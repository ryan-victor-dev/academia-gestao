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
 * Os códigos de 6 dígitos mandados por e-mail (V50, ADR 0038): confirmar o e-mail e recuperar
 * a senha. Valem 15 minutos, uma vez só, com até 5 tentativas; o novo encerra o anterior; no
 * máximo 5 por hora, com 1 minuto entre um e outro. Só o hash fica no banco.
 */
@Component
class CodigosDeEmail {

    enum Finalidade { CONFIRMACAO, RECUPERACAO }

    enum Resultado { CONFERE, NAO_CONFERE }

    static final Duration VALIDADE = Duration.ofMinutes(15);
    static final int TENTATIVAS = 5;
    static final int POR_HORA = 5;
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
     * Um código novo para o e-mail do usuário (o anterior para de valer). Na transação de quem
     * chama; o envio do e-mail vem depois, fora dela.
     *
     * @return o código, para o e-mail (nunca vai para o banco nem para o log)
     * @throws MuitosCodigosException pediu demais em pouco tempo
     */
    @Transactional(propagation = Propagation.MANDATORY)
    String emitir(UUID usuarioId, Finalidade finalidade, String email) {
        Instant agora = relogio.instant();
        // Trava a linha do usuário: dois pedidos ao mesmo tempo contam um depois do outro (sem
        // código nenhum ainda, travar os códigos não travaria nada).
        jdbc.queryForObject("SELECT id FROM usuarios WHERE id = ? FOR UPDATE", UUID.class, usuarioId);
        List<Instant> ultimaHora = jdbc.queryForList("""
                SELECT criado_em FROM codigos_de_email WHERE usuario_id = ? AND finalidade = ? AND criado_em > ?
                ORDER BY criado_em DESC""", Timestamp.class, usuarioId, finalidade.name(),
                Timestamp.from(agora.minus(Duration.ofHours(1)))).stream().map(Timestamp::toInstant).toList();
        if (!ultimaHora.isEmpty() && ultimaHora.get(0).plus(INTERVALO).isAfter(agora)) {
            throw new MuitosCodigosException(Duration.between(agora, ultimaHora.get(0).plus(INTERVALO)));
        }
        if (ultimaHora.size() >= POR_HORA) {
            throw new MuitosCodigosException(Duration.between(agora, ultimaHora.getLast().plus(Duration.ofHours(1))));
        }
        jdbc.update("UPDATE codigos_de_email SET encerrado_em = ? WHERE usuario_id = ? AND finalidade = ? "
                + "AND encerrado_em IS NULL", Timestamp.from(agora), usuarioId, finalidade.name());
        String codigo = String.format("%06d", ALEATORIO.nextInt(1_000_000));
        jdbc.update("""
                INSERT INTO codigos_de_email (id, usuario_id, finalidade, email, codigo_hash, criado_em, expira_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), usuarioId, finalidade.name(), email,
                passwordEncoder.encode(codigo), Timestamp.from(agora), Timestamp.from(agora.plus(VALIDADE)));
        return codigo;
    }

    /**
     * Confere o código do usuário para o e-mail dado, e gasta uma tentativa. Em transação
     * própria: a tentativa errada fica gravada mesmo que quem chamou desfaça a dele. Confere:
     * o código acaba (uso único).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Resultado conferir(UUID usuarioId, Finalidade finalidade, String email, String codigo) {
        Instant agora = relogio.instant();
        List<Map<String, Object>> abertos = jdbc.queryForList("""
                SELECT id, email, codigo_hash, expira_em, tentativas FROM codigos_de_email
                WHERE usuario_id = ? AND finalidade = ? AND encerrado_em IS NULL FOR UPDATE""",
                usuarioId, finalidade.name());
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
