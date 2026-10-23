package br.com.academiagestao.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessões renováveis (ADR 0033). O login abre uma sessão; o cookie dela ("id.segredo") troca o
 * token de acesso vencido por um novo até o fim do prazo, e o segredo muda a cada troca. Só o
 * hash SHA-256 do segredo fica no banco.
 */
@Component
class Sessoes {

    static final String COOKIE = "__Secure-academia-sessao";

    private static final int TAMANHO_SEGREDO = 32;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final UsuarioRepository repository;
    private final GeradorDeToken geradorDeToken;
    private final TransactionTemplate transacao;
    private final SessaoProperties properties;
    private final Clock relogio;

    Sessoes(JdbcTemplate jdbc, UsuarioRepository repository, GeradorDeToken geradorDeToken,
            PlatformTransactionManager transacoes, SessaoProperties properties, Clock relogio) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.geradorDeToken = geradorDeToken;
        this.transacao = new TransactionTemplate(transacoes);
        this.properties = properties;
        this.relogio = relogio;
    }

    /** O valor novo do cookie e por quanto tempo o navegador o guarda (até o fim da sessão). */
    record Cookie(String valor, Duration validade) {
    }

    /** Um token novo e, se o segredo foi trocado, o cookie novo; vazio dentro da folga. */
    record Renovada(GeradorDeToken.TokenEmitido token, Instant sessaoExpiraEm, Optional<Cookie> cookie) {
    }

    record Aberta(Instant expiraEm, Cookie cookie) {
    }

    /** Abre a sessão do usuário que acabou de entrar (ou de trocar a senha). */
    Aberta abrir(UUID usuarioId) {
        Usuario usuario = repository.findById(usuarioId)
                .filter(Usuario::isAtivo)
                .orElseThrow(SessaoInvalidaException::new);
        // Na precisão do banco (microssegundos): o fim devolvido no login é o mesmo que fica gravado.
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiraEm = agora.plus(properties.duracaoPara(usuario.getPerfil()));
        UUID id = UUID.randomUUID();
        byte[] segredo = segredoNovo();
        jdbc.update("""
                INSERT INTO sessoes (id, usuario_id, segredo_hash, versao_token, criada_em, renovada_em, expira_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                id, usuarioId, hash(segredo), usuario.getVersaoToken(),
                Timestamp.from(agora), Timestamp.from(agora), Timestamp.from(expiraEm));
        return new Aberta(expiraEm, new Cookie(valorDoCookie(id, segredo), Duration.between(agora, expiraEm)));
    }

    /**
     * Troca o cookie por um token novo. Segredo já trocado fora da folga (ou desconhecido) é
     * sinal de cópia: a sessão é encerrada, e a recusa é gravada mesmo assim (por isso a
     * exceção sai depois da transação, e não de dentro dela).
     */
    Renovada renovar(String cookie) {
        CookieLido lido = ler(cookie).orElseThrow(SessaoInvalidaException::new);
        Optional<Renovada> renovada = transacao.execute(status -> renovarNaTransacao(lido));
        return renovada.orElseThrow(SessaoInvalidaException::new);
    }

    private Optional<Renovada> renovarNaTransacao(CookieLido lido) {
        List<Linha> linhas = jdbc.query("""
                SELECT usuario_id, segredo_hash, segredo_anterior_hash, versao_token, renovada_em, expira_em,
                       encerrada_em
                FROM sessoes WHERE id = ? FOR UPDATE""",
                (rs, i) -> new Linha(rs.getObject("usuario_id", UUID.class), rs.getBytes("segredo_hash"),
                        rs.getBytes("segredo_anterior_hash"), rs.getInt("versao_token"),
                        rs.getTimestamp("renovada_em").toInstant(), rs.getTimestamp("expira_em").toInstant(),
                        rs.getTimestamp("encerrada_em") != null),
                lido.id());
        if (linhas.isEmpty()) {
            return Optional.empty();
        }
        Linha sessao = linhas.getFirst();
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.MICROS);
        if (sessao.encerrada() || !agora.isBefore(sessao.expiraEm())) {
            return Optional.empty();
        }
        byte[] hash = hash(lido.segredo());
        boolean atual = MessageDigest.isEqual(hash, sessao.segredoHash());
        boolean anteriorNaFolga = sessao.segredoAnteriorHash() != null
                && MessageDigest.isEqual(hash, sessao.segredoAnteriorHash())
                && agora.isBefore(sessao.renovadaEm().plus(properties.folga()));
        Optional<Usuario> usuario = repository.findById(sessao.usuarioId())
                .filter(Usuario::isAtivo)
                .filter(u -> u.getVersaoToken() == sessao.versaoToken());
        if ((!atual && !anteriorNaFolga) || usuario.isEmpty()) {
            jdbc.update("UPDATE sessoes SET encerrada_em = ? WHERE id = ?", Timestamp.from(agora), lido.id());
            return Optional.empty();
        }

        GeradorDeToken.TokenEmitido token = geradorDeToken.gerar(usuario.get(), sessao.expiraEm());
        if (!atual) {
            // Outra aba acabou de trocar o segredo; o cookie novo já está no navegador.
            return Optional.of(new Renovada(token, sessao.expiraEm(), Optional.empty()));
        }
        byte[] segredo = segredoNovo();
        jdbc.update("""
                UPDATE sessoes SET segredo_anterior_hash = segredo_hash, segredo_hash = ?, renovada_em = ?
                WHERE id = ?""", hash(segredo), Timestamp.from(agora), lido.id());
        Cookie novo = new Cookie(valorDoCookie(lido.id(), segredo), Duration.between(agora, sessao.expiraEm()));
        return Optional.of(new Renovada(token, sessao.expiraEm(), Optional.of(novo)));
    }

    /**
     * Sair: encerra a sessão do cookie, se ele for dela (segredo atual ou o anterior). Cookie
     * ausente, inválido ou de sessão já encerrada não é erro.
     */
    void encerrar(String cookie) {
        ler(cookie).ifPresent(lido -> {
            byte[] hash = hash(lido.segredo());
            jdbc.update("""
                    UPDATE sessoes SET encerrada_em = ?
                    WHERE id = ? AND encerrada_em IS NULL AND (segredo_hash = ? OR segredo_anterior_hash = ?)""",
                    Timestamp.from(relogio.instant()), lido.id(), hash, hash);
        });
    }

    /** Apaga as sessões vencidas ou encerradas há mais de 1 dia. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void limparAntigas() {
        Timestamp limite = Timestamp.from(relogio.instant().minus(Duration.ofDays(1)));
        jdbc.update("DELETE FROM sessoes WHERE expira_em < ? OR encerrada_em < ?", limite, limite);
    }

    private record Linha(UUID usuarioId, byte[] segredoHash, byte[] segredoAnteriorHash, int versaoToken,
                         Instant renovadaEm, Instant expiraEm, boolean encerrada) {
    }

    private record CookieLido(UUID id, byte[] segredo) {
    }

    private static String valorDoCookie(UUID id, byte[] segredo) {
        return id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(segredo);
    }

    private static Optional<CookieLido> ler(String cookie) {
        if (cookie == null) {
            return Optional.empty();
        }
        int ponto = cookie.indexOf('.');
        if (ponto < 0) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(cookie.substring(0, ponto));
            byte[] segredo = Base64.getUrlDecoder().decode(cookie.substring(ponto + 1));
            return segredo.length == TAMANHO_SEGREDO ? Optional.of(new CookieLido(id, segredo)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] segredoNovo() {
        byte[] segredo = new byte[TAMANHO_SEGREDO];
        ALEATORIO.nextBytes(segredo);
        return segredo;
    }

    private static byte[] hash(byte[] segredo) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(segredo);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 é obrigatório em toda JVM", e);
        }
    }
}
