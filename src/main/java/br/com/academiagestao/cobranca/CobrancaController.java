package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.CobrancaService.Consulta;
import br.com.academiagestao.cobranca.dominio.Cancelamento;
import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.Pagamento;
import br.com.academiagestao.cobranca.dominio.StatusCobranca;
import br.com.academiagestao.cobranca.dominio.TipoCobranca;
import br.com.academiagestao.cobranca.dominio.ValorDevido;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cobranças (ADR 0024). A recepção consulta e registra pagamentos; só o ADMIN estorna e
 * cancela. O valor devido é calculado no dia, com a configuração de atraso de hoje.
 */
@RestController
@RequestMapping("/api/cobrancas")
class CobrancaController {

    private final CobrancaService service;

    CobrancaController(CobrancaService service) {
        this.service = service;
    }

    /** Todas as do aluno, da que vence primeiro para a última. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    List<CobrancaResponse> doAluno(@RequestParam UUID alunoId) {
        return service.doAluno(alunoId).stream().map(CobrancaResponse::de).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    CobrancaResponse buscar(@PathVariable UUID id) {
        return CobrancaResponse.de(service.buscar(id));
    }

    /** O valor precisa ser o devido hoje (o "devidoHoje.total" da consulta). */
    @PostMapping("/{id}/pagar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    CobrancaResponse pagar(@PathVariable UUID id, @Valid @RequestBody PagamentoRequest request,
                           @AuthenticationPrincipal Jwt jwt) {
        return CobrancaResponse.de(service.pagar(id, request.valor(), request.meioDePagamento(),
                UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/{id}/estornar")
    @PreAuthorize("hasRole('ADMIN')")
    CobrancaResponse estornar(@PathVariable UUID id, @Valid @RequestBody MotivoRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return CobrancaResponse.de(service.estornar(id, request.motivo(), UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/{id}/cancelar")
    @PreAuthorize("hasRole('ADMIN')")
    CobrancaResponse cancelar(@PathVariable UUID id, @Valid @RequestBody MotivoRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return CobrancaResponse.de(service.cancelar(id, request.motivo(), UUID.fromString(jwt.getSubject())));
    }

    record PagamentoRequest(
            @NotNull(message = "é obrigatório") BigDecimal valor,
            @NotNull(message = "é obrigatório") MeioDePagamento meioDePagamento) {
    }

    record MotivoRequest(String motivo) {
    }

    /**
     * {@code situacao}: PENDENTE, ATRASADA (pendente com o vencimento no passado), PAGA ou
     * CANCELADA. {@code devidoHoje} só existe em aberto.
     */
    record CobrancaResponse(UUID id, UUID matriculaId, UUID alunoId, TipoCobranca tipo, int parcela,
                            BigDecimal valor, LocalDate vencimento, String situacao, long diasDeAtraso,
                            DevidoResponse devidoHoje, PagamentoResponse pagamento,
                            CancelamentoResponse cancelamento) {

        static CobrancaResponse de(Consulta consulta) {
            Cobranca c = consulta.cobranca();
            String situacao = c.getStatus() == StatusCobranca.PENDENTE && c.atrasada(consulta.hoje())
                    ? "ATRASADA" : c.getStatus().name();
            return new CobrancaResponse(c.getId(), c.getMatriculaId(), c.getAlunoId(), c.getTipo(), c.getParcela(),
                    c.getValor(), c.getVencimento(), situacao,
                    consulta.devido().map(ValorDevido::diasDeAtraso).orElse(0L),
                    consulta.devido().map(DevidoResponse::de).orElse(null),
                    c.getPagamento() == null ? null : PagamentoResponse.de(c.getPagamento()),
                    c.getCancelamento() == null ? null : CancelamentoResponse.de(c.getCancelamento()));
        }
    }

    record DevidoResponse(BigDecimal valor, BigDecimal acrescimo, BigDecimal juros, BigDecimal total) {
        static DevidoResponse de(ValorDevido devido) {
            return new DevidoResponse(devido.valor(), devido.acrescimo(), devido.juros(), devido.total());
        }
    }

    record PagamentoResponse(BigDecimal total, BigDecimal acrescimo, BigDecimal juros, MeioDePagamento meio,
                             Instant em, UUID por) {
        static PagamentoResponse de(Pagamento p) {
            return new PagamentoResponse(p.total(), p.acrescimo(), p.juros(), p.meio(), p.em(), p.por());
        }
    }

    /** {@code por} nulo: o sistema, ao encerrar a matrícula. */
    record CancelamentoResponse(Instant em, UUID por, String motivo) {
        static CancelamentoResponse de(Cancelamento c) {
            return new CancelamentoResponse(c.em(), c.por(), c.motivo());
        }
    }
}
