package br.com.academiagestao.noticias;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Anexos das publicações (ADR 0047, decisão 6). O ADMIN e a recepção enviam o arquivo cru no corpo (até 50 MB, a
 * exceção ao limite de 64 KB), com o nome no cabeçalho {@code X-Nome-Do-Arquivo} (codificado como na URL). Quem lê
 * baixa pelo endereço assinado que vem na publicação.
 */
@RestController
class AnexoController {

    private final AnexoService service;
    private final AssinaturaDosAnexos assinatura;

    AnexoController(AnexoService service, AssinaturaDosAnexos assinatura) {
        this.service = service;
        this.assinatura = assinatura;
    }

    @PostMapping("/api/publicacoes/{id}/anexos")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<AnexoResponse> enviar(@PathVariable UUID id, @RequestHeader("X-Nome-Do-Arquivo") String nome,
                                         HttpServletRequest requisicao, @AuthenticationPrincipal Jwt jwt) throws IOException {
        Anexos.Anexo anexo = service.receber(id, URLDecoder.decode(nome, StandardCharsets.UTF_8), requisicao.getInputStream(),
                UUID.fromString(jwt.getSubject()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AnexoResponse.de(anexo, assinatura));
    }

    @DeleteMapping("/api/publicacoes/{id}/anexos/{anexoId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<Void> excluir(@PathVariable UUID id, @PathVariable UUID anexoId) {
        service.excluir(id, anexoId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sem login: quem autoriza é a assinatura. Assinatura errada, prazo vencido ou anexo que não existe dão o mesmo
     * 404 (não se aprende nada tentando). O tipo da resposta é o reconhecido no envio; a foto e o vídeo abrem na
     * página, e o PDF vem como download. O cache é só do navegador de quem abriu, até o fim do prazo.
     */
    @GetMapping("/api/anexos/{anexoId}")
    ResponseEntity<Resource> baixar(@PathVariable UUID anexoId, @RequestParam long expira,
                                    @RequestParam String assinatura) {
        if (!this.assinatura.confere(anexoId, expira, assinatura)) {
            throw new AnexoNaoEncontradoException();
        }
        Anexos.Anexo anexo = service.pronto(anexoId);
        Duration restante = this.assinatura.restante(expira);
        ContentDisposition disposicao = (anexo.tipo().grupo() == TipoDeArquivo.Grupo.DOCUMENTO
                ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(anexo.nome(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(anexo.tipo().conteudoTipo()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposicao.toString())
                .cacheControl(CacheControl.maxAge(restante).cachePrivate())
                .body(service.conteudo(anexo));
    }

    record AnexoResponse(UUID id, String tipo, String conteudoTipo, String nome, long tamanho, String endereco) {

        static AnexoResponse de(Anexos.Anexo anexo, AssinaturaDosAnexos assinatura) {
            return new AnexoResponse(anexo.id(), anexo.tipo().grupo().name(), anexo.tipo().conteudoTipo(), anexo.nome(),
                    anexo.tamanho(), assinatura.assinar(anexo.id()).caminho());
        }
    }
}
