package br.com.academiagestao.cobranca;

import br.com.academiagestao.auth.ClaimsDoToken;
import br.com.academiagestao.cobranca.CobrancaService.Consulta;
import br.com.academiagestao.cobranca.dominio.Cancelamento;
import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.Lancamento;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.Pagamento;
import br.com.academiagestao.cobranca.dominio.StatusCobranca;
import br.com.academiagestao.cobranca.dominio.TipoCobranca;
import br.com.academiagestao.cobranca.dominio.ValorDevido;
import br.com.academiagestao.compartilhado.Pagina;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.AccessDeniedException;
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

    /**
     * As cobranças do próprio aluno, com o devido de hoje. O id vem do token, nunca da URL:
     * não há como pedir as de outra pessoa.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ALUNO')")
    List<CobrancaParaAluno> minhas(@AuthenticationPrincipal Jwt jwt) {
        String alunoId = jwt.getClaimAsString(ClaimsDoToken.ALUNO_ID);
        if (alunoId == null) {
            throw new AccessDeniedException("Token de aluno sem alunoId");
        }
        return service.doAluno(UUID.fromString(alunoId)).stream().map(CobrancaParaAluno::de).toList();
    }

    /** Para a recepção cobrar: as em aberto já vencidas, da mais antiga para a mais nova. */
    @GetMapping("/atrasadas")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    Pagina<CobrancaResponse> atrasadas(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
        Pagina<Consulta> consultas = service.atrasadas(pagina, tamanho);
        return new Pagina<>(consultas.conteudo().stream().map(CobrancaResponse::de).toList(), consultas.pagina(),
                consultas.tamanho(), consultas.totalElementos(), consultas.totalPaginas());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    CobrancaResponse buscar(@PathVariable UUID id) {
        return CobrancaResponse.de(service.buscar(id));
    }

    /** O registro de pagamentos e estornos da cobrança, na ordem (ADR 0025): nada some no estorno. */
    @GetMapping("/{id}/lancamentos")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    List<LancamentoResponse> lancamentos(@PathVariable UUID id) {
        return service.lancamentos(id).stream().map(LancamentoResponse::de).toList();
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

    /** O que o aluno vê: sem os ids de quem registrou ou cancelou (dados da equipe). */
    record CobrancaParaAluno(UUID id, TipoCobranca tipo, int parcela, BigDecimal valor, LocalDate vencimento,
                             String situacao, long diasDeAtraso, DevidoResponse devidoHoje, Instant pagaEm,
                             MeioDePagamento meioDePagamento, BigDecimal pagoTotal) {
        static CobrancaParaAluno de(Consulta consulta) {
            CobrancaResponse completa = CobrancaResponse.de(consulta);
            Pagamento pagamento = consulta.cobranca().getPagamento();
            return new CobrancaParaAluno(completa.id(), completa.tipo(), completa.parcela(), completa.valor(),
                    completa.vencimento(), completa.situacao(), completa.diasDeAtraso(), completa.devidoHoje(),
                    pagamento == null ? null : pagamento.em(), pagamento == null ? null : pagamento.meio(),
                    pagamento == null ? null : pagamento.total());
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

    /** {@code estorna} e {@code motivo} só no estorno. */
    record LancamentoResponse(UUID id, Lancamento.Tipo tipo, UUID estorna, BigDecimal valor, BigDecimal acrescimo,
                              BigDecimal juros, BigDecimal total, MeioDePagamento meio, Instant em, UUID por,
                              String motivo) {
        static LancamentoResponse de(Lancamento l) {
            return new LancamentoResponse(l.id(), l.tipo(), l.estorna(), l.valor(), l.acrescimo(), l.juros(), l.total(),
                    l.meio(), l.em(), l.por(), l.motivo());
        }
    }

    /** {@code por} nulo: o sistema, ao encerrar a matrícula. */
    record CancelamentoResponse(Instant em, UUID por, String motivo) {
        static CancelamentoResponse de(Cancelamento c) {
            return new CancelamentoResponse(c.em(), c.por(), c.motivo());
        }
    }
}
