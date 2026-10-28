package br.com.academiagestao.pagamentos;

import br.com.academiagestao.compartilhado.VersaoEsperada;
import br.com.academiagestao.pagamentos.ConfiguracaoDoPagamentoOnline.Adiantar;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
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

import java.util.List;

/**
 * O que o ADMIN decide do pagamento online (ADR 0034), com If-Match, na trilha. A empresa em
 * si não se escolhe aqui: é do {@code .env}, com as chaves dela.
 */
@RestController
@RequestMapping("/api/pagamentos-online/configuracao")
class ConfiguracaoDoPagamentoOnlineController {

    private final ConfiguracaoDoPagamentoOnlineService service;
    private final EmpresaDePagamento empresa;

    ConfiguracaoDoPagamentoOnlineController(ConfiguracaoDoPagamentoOnlineService service,
                                            EmpresaDePagamento empresa) {
        this.service = service;
        this.empresa = empresa;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConfiguracaoResponse> ler() {
        return comEtag(service.ler());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConfiguracaoResponse> alterar(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConfiguracaoRequest request) {
        return comEtag(service.alterar(new ConfiguracaoDoPagamentoOnlineService.Escolhas(request.pixLigado(),
                request.boletoLigado(), request.creditoLigado(), request.debitoLigado(), request.adiantar(),
                request.diasDoBoleto()), ifMatch));
    }

    private ResponseEntity<ConfiguracaoResponse> comEtag(ConfiguracaoDoPagamentoOnline c) {
        return ResponseEntity.ok().eTag(VersaoEsperada.etag(c.versao())).body(new ConfiguracaoResponse(
                empresa.provedorLigado(), empresa.simulado(), c.pixLigado(), c.boletoLigado(),
                c.creditoLigado(), c.debitoLigado(), c.adiantar(), c.diasDoBoleto(), empresa.formasDaEmpresa()));
    }

    record ConfiguracaoRequest(
            @NotNull(message = "é obrigatório") Boolean pixLigado,
            @NotNull(message = "é obrigatório") Boolean boletoLigado,
            @NotNull(message = "é obrigatório") Boolean creditoLigado,
            @NotNull(message = "é obrigatório") Boolean debitoLigado,
            @NotNull(message = "é obrigatório") Adiantar adiantar,
            @NotNull(message = "é obrigatório") Integer diasDoBoleto) {
    }

    /**
     * {@code provedor}: a empresa do {@code .env} ({@code desligado}: o app não oferece pagar).
     * {@code formasDaEmpresa}: as que ela oferece; ligar uma que ela não tem não faz nada aparecer.
     */
    record ConfiguracaoResponse(String provedor, boolean simulado, boolean pixLigado, boolean boletoLigado,
                                boolean creditoLigado, boolean debitoLigado, Adiantar adiantar, int diasDoBoleto,
                                List<Forma> formasDaEmpresa) {
    }
}
