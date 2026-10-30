package br.com.academiagestao.noticias;

import br.com.academiagestao.noticias.DadosDaPublicacao.Link;
import br.com.academiagestao.noticias.DadosDaPublicacao.Tipo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** As publicações e os links delas no banco (V57). */
@Repository
class Publicacoes {

    private static final String SELECT = """
            SELECT p.id, p.tipo, p.titulo, p.texto, p.evento_inicio, p.evento_fim, p.evento_local, p.criada_em,
                   p.criada_por, u.nome AS autor_nome, p.editada_em
            FROM publicacoes p JOIN usuarios u ON u.id = p.criada_por""";

    private final JdbcTemplate jdbc;
    private final Anexos anexos;

    Publicacoes(JdbcTemplate jdbc, Anexos anexos) {
        this.jdbc = jdbc;
        this.anexos = anexos;
    }

    void inserir(UUID id, DadosDaPublicacao dados, Instant agora, UUID autor) {
        jdbc.update("""
                INSERT INTO publicacoes (id, tipo, titulo, texto, evento_inicio, evento_fim, evento_local, criada_em, criada_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, dados.tipo().name(), dados.titulo(), dados.texto(),
                timestamp(dados.eventoInicio()), timestamp(dados.eventoFim()), dados.eventoLocal(), timestamp(agora), autor);
        gravarLinks(id, dados.links());
    }

    /** @return se a publicação existia */
    boolean atualizar(UUID id, DadosDaPublicacao dados, Instant agora, UUID quem) {
        int alteradas = jdbc.update("""
                UPDATE publicacoes SET tipo = ?, titulo = ?, texto = ?, evento_inicio = ?, evento_fim = ?, evento_local = ?,
                                       editada_em = ?, editada_por = ?
                WHERE id = ?""", dados.tipo().name(), dados.titulo(), dados.texto(), timestamp(dados.eventoInicio()),
                timestamp(dados.eventoFim()), dados.eventoLocal(), timestamp(agora), quem, id);
        if (alteradas == 0) {
            return false;
        }
        jdbc.update("DELETE FROM publicacao_links WHERE publicacao_id = ?", id);
        gravarLinks(id, dados.links());
        return true;
    }

    /** Os links saem junto (chave estrangeira em cascata). @return se a publicação existia */
    boolean excluir(UUID id) {
        return jdbc.update("DELETE FROM publicacoes WHERE id = ?", id) > 0;
    }

    /** Trava a linha até o fim da transação: edição e exclusão ao mesmo tempo esperam uma pela outra. */
    Optional<Publicacao> travar(UUID id) {
        if (jdbc.queryForList("SELECT 1 FROM publicacoes WHERE id = ? FOR UPDATE", Integer.class, id).isEmpty()) {
            return Optional.empty();
        }
        return buscar(id);
    }

    Optional<Publicacao> buscar(UUID id) {
        List<Publicacao> encontradas = comLinks(jdbc.query(SELECT + " WHERE p.id = ?", Publicacoes::semLinks, id));
        return encontradas.stream().findFirst();
    }

    /** Da mais nova para a mais antiga, com filtro opcional pelo tipo. */
    Page<Publicacao> listar(Tipo tipo, int pagina, int tamanho) {
        String filtro = tipo == null ? "" : " WHERE p.tipo = ?";
        Object[] parametros = tipo == null ? new Object[0] : new Object[] {tipo.name()};
        long total = jdbc.queryForObject("SELECT count(*) FROM publicacoes p" + filtro, Long.class, parametros);
        List<Object> comLimite = new ArrayList<>(List.of(parametros));
        comLimite.add(tamanho);
        comLimite.add((long) pagina * tamanho);
        List<Publicacao> conteudo = comLinks(jdbc.query(SELECT + filtro + " ORDER BY p.criada_em DESC, p.id LIMIT ? OFFSET ?",
                Publicacoes::semLinks, comLimite.toArray()));
        return new PageImpl<>(conteudo, PageRequest.of(pagina, tamanho), total);
    }

    /** Os eventos que ainda não terminaram (sem fim: os que não começaram há mais de um dia), pelo início. */
    List<Publicacao> proximosEventos(Instant agora, int limite) {
        return comLinks(jdbc.query(SELECT + " WHERE p.tipo = 'EVENTO'"
                + " AND coalesce(p.evento_fim, p.evento_inicio + interval '1 day') >= ?"
                + " ORDER BY p.evento_inicio, p.id LIMIT ?", Publicacoes::semLinks, timestamp(agora), limite));
    }

    private void gravarLinks(UUID id, List<Link> links) {
        for (int ordem = 0; ordem < links.size(); ordem++) {
            jdbc.update("INSERT INTO publicacao_links (publicacao_id, ordem, url, rotulo) VALUES (?, ?, ?, ?)",
                    id, ordem, links.get(ordem).url(), links.get(ordem).rotulo());
        }
    }

    /** Os links e os anexos de todas as publicações da página numa consulta cada (e não uma por publicação). */
    private List<Publicacao> comLinks(List<Publicacao> publicacoes) {
        if (publicacoes.isEmpty()) {
            return publicacoes;
        }
        Map<UUID, List<Link>> links = new LinkedHashMap<>();
        publicacoes.forEach(p -> links.put(p.id(), new ArrayList<>()));
        jdbc.query("SELECT publicacao_id, url, rotulo FROM publicacao_links WHERE publicacao_id = ANY (?) ORDER BY publicacao_id, ordem",
                rs -> { links.get(rs.getObject(1, UUID.class)).add(new Link(rs.getString(2), rs.getString(3))); },
                (Object) publicacoes.stream().map(Publicacao::id).toArray(UUID[]::new));
        Map<UUID, List<Anexos.Anexo>> prontos = anexos.prontosDas(links.keySet());
        return publicacoes.stream().map(p -> new Publicacao(p.id(), comLinks(p.dados(), links.get(p.id())), p.criadaEm(),
                p.criadaPor(), p.autorNome(), p.editadaEm(), prontos.getOrDefault(p.id(), List.of()))).toList();
    }

    private static DadosDaPublicacao comLinks(DadosDaPublicacao d, List<Link> links) {
        return new DadosDaPublicacao(d.tipo(), d.titulo(), d.texto(), d.eventoInicio(), d.eventoFim(), d.eventoLocal(), links);
    }

    private static Publicacao semLinks(ResultSet rs, int linha) throws SQLException {
        DadosDaPublicacao dados = new DadosDaPublicacao(Tipo.valueOf(rs.getString("tipo")), rs.getString("titulo"),
                rs.getString("texto"), instante(rs, "evento_inicio"), instante(rs, "evento_fim"), rs.getString("evento_local"),
                List.of());
        return new Publicacao(rs.getObject("id", UUID.class), dados, instante(rs, "criada_em"),
                rs.getObject("criada_por", UUID.class), rs.getString("autor_nome"), instante(rs, "editada_em"), List.of());
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp valor = rs.getTimestamp(coluna);
        return valor == null ? null : valor.toInstant();
    }

    private static Timestamp timestamp(Instant instante) {
        return instante == null ? null : Timestamp.from(instante);
    }
}
