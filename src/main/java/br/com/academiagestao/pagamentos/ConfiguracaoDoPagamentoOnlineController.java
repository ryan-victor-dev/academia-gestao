package br.com.academiagestao.pagamentos;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import br.com.academiagestao.pagamentos.ConfiguracaoDoPagamentoOnline.Adiantar;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.CONFIGURACAO;

/**
 * O que o ADMIN decide do pagamento online (ADR 0034), com If-Match, na trilha. A empresa em
 * si não se escolhe aqui: é do {@code .env}, com as chaves dela.
 */
@RestController
@RequestMapping("/api/pagamentos-online/configuracao")
class ConfiguracaoDoPagamentoOnlineController {

    /** A trilha pede um id; a configuração é uma só. */
    static final UUID ID_NA_TRILHA = UUID.nameUUIDFromBytes(
            "configuracao-pagamento-online".getBytes(StandardCharsets.UTF_8));

    private final ConfiguracaoDoPagamentoOnlineRepository repository;
    private final PagamentoOnlineService pagamentos;
    private final Auditoria auditoria;

    ConfiguracaoDoPagamentoOnlineController(ConfiguracaoDoPagamentoOnlineRepository repository,
                                            PagamentoOnlineService pagamentos, Auditoria auditoria) {
        this.repository = repository;
        this.pagamentos = pagamentos;
        this.auditoria = auditoria;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ResponseEntity<ConfiguracaoResponse> ler() {
        return comEtag(repository.ler());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<ConfiguracaoResponse> alterar(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConfiguracaoRequest request) {
        ConfiguracaoDoPagamentoOnline atual = repository.travar();
        VersaoEsperada.conferir(ifMatch, atual.versao(), "a configuração do pagamento online");
        ConfiguracaoDoPagamentoOnline gravada = repository.gravar(new ConfiguracaoDoPagamentoOnline(
                request.pixLigado(), request.boletoLigado(), request.adiantar(), request.diasDoBoleto(), atual.versao()));
        auditoria.registrar(CONFIGURACAO, ID_NA_TRILHA, "EDITADA", retrato(atual), retrato(gravada));
        return comEtag(gravada);
    }

    private ResponseEntity<ConfiguracaoResponse> comEtag(ConfiguracaoDoPagamentoOnline c) {
        return ResponseEntity.ok().eTag(VersaoEsperada.etag(c.versao())).body(new ConfiguracaoResponse(
                pagamentos.provedorLigado(), pagamentos.simulado(), c.pixLigado(), c.boletoLigado(), c.adiantar(),
                c.diasDoBoleto()));
    }

    private static Map<String, Object> retrato(ConfiguracaoDoPagamentoOnline c) {
        return Auditoria.campos("pixLigado", c.pixLigado(), "boletoLigado", c.boletoLigado(),
                "adiantar", c.adiantar().name(), "diasDoBoleto", c.diasDoBoleto());
    }

    record ConfiguracaoRequest(
            @NotNull(message = "é obrigatório") Boolean pixLigado,
            @NotNull(message = "é obrigatório") Boolean boletoLigado,
            @NotNull(message = "é obrigatório") Adiantar adiantar,
            @NotNull(message = "é obrigatório") Integer diasDoBoleto) {
    }

    /** {@code provedor}: a empresa do {@code .env} ({@code desligado}: o app não oferece pagar). */
    record ConfiguracaoResponse(String provedor, boolean simulado, boolean pixLigado, boolean boletoLigado,
                                Adiantar adiantar, int diasDoBoleto) {
    }
}
