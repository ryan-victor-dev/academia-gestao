package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.CaixaService.Consulta;
import br.com.academiagestao.cobranca.dominio.Caixa;
import br.com.academiagestao.cobranca.dominio.Caixa.Fechamento;
import br.com.academiagestao.cobranca.dominio.Caixa.Reabertura;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * O caixa do dia (ADR 0031). A recepção (ou o ADMIN) abre o de hoje com o troco e fecha com o
 * dinheiro contado; só o ADMIN reabre um dia fechado, com motivo. Sem caixa aberto, pagamento e
 * estorno são recusados.
 */
@RestController
@RequestMapping("/api/caixas")
class CaixaController {

    private final CaixaService service;

    CaixaController(CaixaService service) {
        this.service = service;
    }

    /** Abre o caixa de hoje (o dia vem do relógio, no fuso da academia). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    CaixaResponse abrir(@Valid @RequestBody AberturaRequest request, @AuthenticationPrincipal Jwt jwt) {
        return CaixaResponse.de(service.abrir(request.troco(), UUID.fromString(jwt.getSubject())));
    }

    @GetMapping("/{dia}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    CaixaResponse doDia(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dia) {
        return CaixaResponse.de(service.doDia(dia));
    }

    /** Com o dinheiro contado na gaveta; diferença do esperado exige justificativa. */
    @PostMapping("/{dia}/fechar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    CaixaResponse fechar(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dia,
                         @Valid @RequestBody FechamentoRequest request, @AuthenticationPrincipal Jwt jwt) {
        return CaixaResponse.de(service.fechar(dia, request.contado(), request.justificativa(),
                UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/{dia}/reabrir")
    @PreAuthorize("hasRole('ADMIN')")
    CaixaResponse reabrir(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dia,
                          @RequestBody MotivoRequest request, @AuthenticationPrincipal Jwt jwt) {
        return CaixaResponse.de(service.reabrir(dia, request.motivo(), UUID.fromString(jwt.getSubject())));
    }

    record AberturaRequest(@NotNull(message = "é obrigatório") BigDecimal troco) {
    }

    record FechamentoRequest(@NotNull(message = "é obrigatório") BigDecimal contado, String justificativa) {
    }

    record MotivoRequest(String motivo) {
    }

    /**
     * Sem o esperado enquanto aberto: quem conta a gaveta não vê antes quanto deveria dar. O
     * esperado e a diferença aparecem em cada fechamento.
     */
    record CaixaResponse(UUID id, LocalDate dia, BigDecimal troco, Caixa.Situacao situacao, Instant abertoEm,
                         UUID abertoPor, List<FechamentoResponse> fechamentos, List<ReaberturaResponse> reaberturas) {
        static CaixaResponse de(Consulta consulta) {
            Caixa c = consulta.caixa();
            return new CaixaResponse(c.getId(), c.getDia(), c.getTroco(), c.getSituacao(), c.getAbertoEm(),
                    c.getAbertoPor(), consulta.fechamentos().stream().map(FechamentoResponse::de).toList(),
                    consulta.reaberturas().stream().map(ReaberturaResponse::de).toList());
        }
    }

    record FechamentoResponse(BigDecimal contado, BigDecimal esperado, BigDecimal diferenca, String justificativa,
                              Instant em, UUID por) {
        static FechamentoResponse de(Fechamento f) {
            return new FechamentoResponse(f.contado(), f.esperado(), f.diferenca(), f.justificativa(), f.em(), f.por());
        }
    }

    record ReaberturaResponse(String motivo, Instant em, UUID por) {
        static ReaberturaResponse de(Reabertura r) {
            return new ReaberturaResponse(r.motivo(), r.em(), r.por());
        }
    }
}
