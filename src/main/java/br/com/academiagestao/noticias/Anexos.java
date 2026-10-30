package br.com.academiagestao.noticias;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Os anexos das publicações e os pedaços deles no banco (V58). */
@Repository
class Anexos {

    /** O que se mostra de um anexo pronto (sem o conteúdo). */
    record Anexo(UUID id, UUID publicacaoId, TipoDeArquivo tipo, String nome, long tamanho) {
    }

    private final JdbcTemplate jdbc;

    Anexos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void criarEnviando(UUID id, UUID publicacaoId, TipoDeArquivo tipo, String nome, Instant agora, UUID autor) {
        jdbc.update("""
                INSERT INTO publicacao_anexos (id, publicacao_id, tipo, conteudo_tipo, nome, tamanho, situacao, criado_em, criado_por)
                VALUES (?, ?, ?, ?, ?, 1, 'ENVIANDO', ?, ?)""", id, publicacaoId, tipo.grupo().name(), tipo.conteudoTipo(), nome,
                Timestamp.from(agora), autor);
    }

    void gravarParte(UUID anexoId, int ordem, byte[] dados) {
        jdbc.update("INSERT INTO anexo_partes (anexo_id, ordem, dados) VALUES (?, ?, ?)", anexoId, ordem, dados);
    }

    void concluir(UUID anexoId, long tamanho) {
        jdbc.update("UPDATE publicacao_anexos SET situacao = 'PRONTO', tamanho = ? WHERE id = ?", tamanho, anexoId);
    }

    int prontosDaPublicacao(UUID publicacaoId) {
        return jdbc.queryForObject("SELECT count(*) FROM publicacao_anexos WHERE publicacao_id = ? AND situacao = 'PRONTO'",
                Integer.class, publicacaoId);
    }

    /** Tudo o que está guardado, inclusive o que ainda está chegando. */
    long totalGuardado() {
        return jdbc.queryForObject("SELECT coalesce(sum(octet_length(dados)), 0) FROM anexo_partes", Long.class);
    }

    Optional<Anexo> pronto(UUID anexoId) {
        return jdbc.query("SELECT id, publicacao_id, conteudo_tipo, nome, tamanho FROM publicacao_anexos WHERE id = ? AND situacao = 'PRONTO'",
                (rs, n) -> new Anexo(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        TipoDeArquivo.doConteudoTipo(rs.getString(3)), rs.getString(4), rs.getLong(5)), anexoId).stream().findFirst();
    }

    /** Os prontos de cada publicação, na ordem em que chegaram. */
    Map<UUID, List<Anexo>> prontosDas(Collection<UUID> publicacoes) {
        Map<UUID, List<Anexo>> porPublicacao = new HashMap<>();
        if (publicacoes.isEmpty()) {
            return porPublicacao;
        }
        jdbc.query("""
                SELECT id, publicacao_id, conteudo_tipo, nome, tamanho FROM publicacao_anexos
                WHERE publicacao_id = ANY (?) AND situacao = 'PRONTO' ORDER BY criado_em, id""",
                rs -> {
                    Anexo anexo = new Anexo(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            TipoDeArquivo.doConteudoTipo(rs.getString(3)), rs.getString(4), rs.getLong(5));
                    porPublicacao.computeIfAbsent(anexo.publicacaoId(), p -> new ArrayList<>()).add(anexo);
                }, (Object) publicacoes.toArray(UUID[]::new));
        return porPublicacao;
    }

    /**
     * Um trecho de um pedaço ({@code inicio} contado de 0 dentro dele). Com o pedaço guardado sem compressão (STORAGE
     * EXTERNAL, V58), o banco lê só o trecho, e não o pedaço inteiro. Vazio se o pedaço não existe.
     */
    Optional<byte[]> trecho(UUID anexoId, int ordem, int inicio, int tamanho) {
        return jdbc.query("SELECT substring(dados FROM ? FOR ?) FROM anexo_partes WHERE anexo_id = ? AND ordem = ?",
                (rs, n) -> rs.getBytes(1), inicio + 1, tamanho, anexoId, ordem).stream().findFirst();
    }

    /** Os pedaços saem junto (chave estrangeira em cascata). @return se existia */
    boolean excluir(UUID anexoId) {
        return jdbc.update("DELETE FROM publicacao_anexos WHERE id = ?", anexoId) > 0;
    }

    /** Envios interrompidos: o que ficou ENVIANDO desde antes de {@code antesDe}. @return quantos saíram */
    int excluirEnviosParados(Instant antesDe) {
        return jdbc.update("DELETE FROM publicacao_anexos WHERE situacao = 'ENVIANDO' AND criado_em < ?", Timestamp.from(antesDe));
    }
}
