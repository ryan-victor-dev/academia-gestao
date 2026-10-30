package br.com.academiagestao.noticias;

import br.com.academiagestao.compartilhado.Pagina;
import br.com.academiagestao.noticias.DadosDaPublicacao.Link;
import br.com.academiagestao.noticias.DadosDaPublicacao.Tipo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Notícias, comunicados e eventos da academia (ADR 0047, decisão 6): a página inicial de todos que têm login.
 * O ADMIN e a recepção publicam, editam e excluem.
 */
@RestController
@RequestMapping("/api/publicacoes")
class PublicacaoController {

    private final PublicacaoService service;

    PublicacaoController(PublicacaoService service) {
        this.service = service;
    }

    /** Da mais nova para a mais antiga; {@code tipo} filtra (NOTICIA, COMUNICADO ou EVENTO). */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    Pagina<PublicacaoResponse> listar(@RequestParam(required = false) Tipo tipo,
                                      @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
                                      @RequestParam(defaultValue = "10") @Min(value = 1, message = "deve ser pelo menos {value}")
                                      @Max(value = 50, message = "pode ser no máximo {value}") int tamanho) {
        return Pagina.de(service.listar(tipo, pagina, tamanho), PublicacaoResponse::de);
    }

    /** Os eventos que ainda não terminaram, pelo início (até {@value PublicacaoService#PROXIMOS_EVENTOS}). */
    @GetMapping("/proximos-eventos")
    @PreAuthorize("isAuthenticated()")
    List<PublicacaoResponse> proximosEventos() {
        return service.proximosEventos().stream().map(PublicacaoResponse::de).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    PublicacaoResponse buscar(@PathVariable UUID id) {
        return PublicacaoResponse.de(service.buscar(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<PublicacaoResponse> publicar(@RequestBody PublicacaoRequest pedido, @AuthenticationPrincipal Jwt jwt) {
        Publicacao publicada = service.publicar(pedido.paraDados(), UUID.fromString(jwt.getSubject()));
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(publicada.id()).toUri();
        return ResponseEntity.created(location).body(PublicacaoResponse.de(publicada));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    PublicacaoResponse editar(@PathVariable UUID id, @RequestBody PublicacaoRequest pedido, @AuthenticationPrincipal Jwt jwt) {
        return PublicacaoResponse.de(service.editar(id, pedido.paraDados(), UUID.fromString(jwt.getSubject())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<Void> excluir(@PathVariable UUID id) {
        service.excluir(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /** As regras ficam em {@link DadosDaPublicacao}: a quebra delas vira 400 com a mensagem. */
    record PublicacaoRequest(Tipo tipo, String titulo, String texto, Instant eventoInicio, Instant eventoFim,
                             String eventoLocal, List<LinkRequest> links) {

        DadosDaPublicacao paraDados() {
            return new DadosDaPublicacao(tipo, titulo, texto, eventoInicio, eventoFim, eventoLocal,
                    links == null ? List.of() : links.stream().map(l -> l == null ? null : new Link(l.url(), l.rotulo())).toList());
        }
    }

    record LinkRequest(String url, String rotulo) {
    }

    record LinkResponse(String url, String rotulo) {
    }

    record PublicacaoResponse(UUID id, Tipo tipo, String titulo, String texto, Instant eventoInicio, Instant eventoFim,
                              String eventoLocal, List<LinkResponse> links, Instant criadaEm, String autorNome,
                              Instant editadaEm) {

        static PublicacaoResponse de(Publicacao p) {
            DadosDaPublicacao d = p.dados();
            return new PublicacaoResponse(p.id(), d.tipo(), d.titulo(), d.texto(), d.eventoInicio(), d.eventoFim(),
                    d.eventoLocal(), p.links().stream().map(l -> new LinkResponse(l.url(), l.rotulo())).toList(),
                    p.criadaEm(), p.autorNome(), p.editadaEm());
        }
    }
}
