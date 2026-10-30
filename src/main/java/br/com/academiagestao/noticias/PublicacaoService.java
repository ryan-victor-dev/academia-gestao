package br.com.academiagestao.noticias;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.noticias.DadosDaPublicacao.Tipo;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.PUBLICACAO;

/**
 * Notícias, comunicados e eventos (ADR 0047, decisão 6). Quem chama já conferiu o perfil: o ADMIN e a
 * recepção publicam, editam e excluem; todos que têm login leem. Cada alteração vai para a trilha.
 */
@Service
class PublicacaoService {

    static final int PROXIMOS_EVENTOS = 5;

    private final Publicacoes publicacoes;
    private final Auditoria auditoria;
    private final Clock relogio;

    PublicacaoService(Publicacoes publicacoes, Auditoria auditoria, Clock relogio) {
        this.publicacoes = publicacoes;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    Page<Publicacao> listar(Tipo tipo, int pagina, int tamanho) {
        return publicacoes.listar(tipo, pagina, tamanho);
    }

    @Transactional(readOnly = true)
    List<Publicacao> proximosEventos() {
        return publicacoes.proximosEventos(relogio.instant(), PROXIMOS_EVENTOS);
    }

    @Transactional(readOnly = true)
    Publicacao buscar(UUID id) {
        return publicacoes.buscar(id).orElseThrow(PublicacaoNaoEncontradaException::new);
    }

    @Transactional
    Publicacao publicar(DadosDaPublicacao dados, UUID autor) {
        UUID id = UUID.randomUUID();
        publicacoes.inserir(id, dados, relogio.instant(), autor);
        auditoria.registrar(PUBLICACAO, id, "PUBLICADA", Map.of(), retrato(dados));
        return buscar(id);
    }

    @Transactional
    Publicacao editar(UUID id, DadosDaPublicacao dados, UUID quem) {
        Publicacao antes = publicacoes.travar(id).orElseThrow(PublicacaoNaoEncontradaException::new);
        Instant agora = relogio.instant();
        publicacoes.atualizar(id, dados, agora, quem);
        auditoria.registrar(PUBLICACAO, id, "EDITADA", retrato(antes.dados()), retrato(dados));
        return buscar(id);
    }

    @Transactional
    void excluir(UUID id) {
        Publicacao antes = publicacoes.travar(id).orElseThrow(PublicacaoNaoEncontradaException::new);
        publicacoes.excluir(id);
        auditoria.registrar(PUBLICACAO, id, "EXCLUIDA", retrato(antes.dados()), Map.of());
    }

    private static Map<String, Object> retrato(DadosDaPublicacao d) {
        return Auditoria.campos("tipo", d.tipo(), "titulo", d.titulo(), "texto", d.texto(), "eventoInicio", d.eventoInicio(),
                "eventoFim", d.eventoFim(), "eventoLocal", d.eventoLocal(),
                "links", d.links().stream().map(DadosDaPublicacao.Link::url).toList());
    }
}
