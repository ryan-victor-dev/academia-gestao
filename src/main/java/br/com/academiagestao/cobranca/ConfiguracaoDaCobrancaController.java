package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.ConfiguracaoDaCobrancaRepository.ConfiguracaoDaCobranca;
import br.com.academiagestao.cobranca.dominio.RegrasDeAtraso;
import br.com.academiagestao.cobranca.dominio.TipoAcrescimo;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Acréscimo, juros e dias para bloquear (ADR 0024). A recepção consulta; só o ADMIN altera,
 * com If-Match, e a mudança vale para todos a partir dela.
 */
@RestController
@RequestMapping("/api/cobrancas/configuracao")
class ConfiguracaoDaCobrancaController {

    private final ConfiguracaoDaCobrancaService service;

    ConfiguracaoDaCobrancaController(ConfiguracaoDaCobrancaService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<ConfiguracaoResponse> ler() {
        return comEtag(service.ler());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ConfiguracaoResponse> alterar(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConfiguracaoRequest request) {
        return comEtag(service.alterar(request.paraRegras(), ifMatch));
    }

    private static ResponseEntity<ConfiguracaoResponse> comEtag(ConfiguracaoDaCobranca configuracao) {
        return ResponseEntity.ok().eTag(VersaoEsperada.etag(configuracao.versao()))
                .body(ConfiguracaoResponse.de(configuracao.regras()));
    }

    record ConfiguracaoRequest(
            @NotNull(message = "é obrigatório") TipoAcrescimo tipoAcrescimo,
            @NotNull(message = "é obrigatório") BigDecimal acrescimo,
            @NotNull(message = "é obrigatório") BigDecimal jurosAoMes,
            @NotNull(message = "é obrigatório") Integer diasParaBloquear) {

        RegrasDeAtraso paraRegras() {
            return new RegrasDeAtraso(tipoAcrescimo, acrescimo, jurosAoMes, diasParaBloquear);
        }
    }

    /** {@code tetoDoAcrescimoPercentual}: para a tela explicar o corte em 2% (CDC). */
    record ConfiguracaoResponse(TipoAcrescimo tipoAcrescimo, BigDecimal acrescimo, BigDecimal jurosAoMes,
                                int diasParaBloquear, BigDecimal tetoDoAcrescimoPercentual) {
        static ConfiguracaoResponse de(RegrasDeAtraso regras) {
            return new ConfiguracaoResponse(regras.tipoAcrescimo(), regras.acrescimo(), regras.jurosAoMes(),
                    regras.diasParaBloquear(), RegrasDeAtraso.TETO_DO_ACRESCIMO_PERCENTUAL);
        }
    }
}
