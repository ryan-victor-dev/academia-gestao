package br.com.academiagestao.noticias;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Uma publicação como a página mostra: os dados, os anexos prontos, quem publicou e se foi editada. */
record Publicacao(UUID id, DadosDaPublicacao dados, Instant criadaEm, UUID criadaPor, String autorNome,
                  Instant editadaEm, List<Anexos.Anexo> anexos) {

    List<DadosDaPublicacao.Link> links() {
        return dados.links();
    }
}
