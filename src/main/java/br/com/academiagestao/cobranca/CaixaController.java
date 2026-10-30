package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.CaixaService.Consulta;
import br.com.academiagestao.cobranca.CaixaService.DiaDoRelatorio;
import br.com.academiagestao.cobranca.CaixaService.Relatorio;
import br.com.academiagestao.cobranca.dominio.Caixa;
import br.com.academiagestao.cobranca.dominio.Caixa.Fechamento;
import br.com.academiagestao.cobranca.dominio.Caixa.Reabertura;
import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.MovimentoDoDia;
import br.com.academiagestao.cobranca.dominio.MovimentoDoDia.Movimento;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O caixa do dia (ADR 0031). A recepção (ou o ADMIN) abre o de hoje com o troco e fecha com o
 * dinheiro contado; só o ADMIN reabre um dia fechado, com motivo. Sem caixa aberto, pagamento e
 * estorno são recusados. O ADMIN vê o relatório de qualquer período; a recepção, só o de hoje e
 * o dos dias abertos, com a contagem às cegas enquanto aberto.
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
    CaixaResponse abrir(@Valid @RequestBody AberturaRequest request, @AuthenticationPrincipal Jwt jwt,
                        Authentication autenticacao) {
        return visao(service.abrir(request.troco(), UUID.fromString(jwt.getSubject())), autenticacao);
    }

    /** Os dias ainda abertos: o que falta fechar (um dia anterior aberto impede abrir o de hoje). */
    @GetMapping("/abertos")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    List<CaixaAbertoResponse> abertos() {
        return service.abertos().stream().map(CaixaAbertoResponse::de).toList();
    }

    /**
     * Só o ADMIN: cada dia do período, quanto entrou e saiu em cada forma, e os fechamentos
     * com o troco, o esperado, o contado, a diferença e a justificativa. Até 366 dias.
     */
    @GetMapping("/relatorio")
    @PreAuthorize("hasRole('ADMIN')")
    RelatorioResponse relatorio(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return RelatorioResponse.de(service.relatorio(de, ate));
    }

    /** A recepção vê só hoje e os dias ainda abertos; o ADMIN, qualquer dia (decisão do dono). */
    @GetMapping("/{dia}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    CaixaResponse doDia(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dia,
                        Authentication autenticacao) {
        Consulta consulta = service.doDia(dia);
        if (ehRecepcao(autenticacao) && !consulta.caixa().recepcaoVe(consulta.hoje())) {
            throw new AccessDeniedException("A recepção vê só o caixa de hoje e os dias ainda abertos");
        }
        return visao(consulta, autenticacao);
    }

    /**
     * Com o dinheiro contado na gaveta; diferença do esperado exige justificativa. A resposta
     * mostra o fechamento a quem fechou, mesmo de um dia passado.
     */
    @PostMapping("/{dia}/fechar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    CaixaResponse fechar(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dia,
                         @Valid @RequestBody FechamentoRequest request, @AuthenticationPrincipal Jwt jwt,
                         Authentication autenticacao) {
        return visao(service.fechar(dia, request.contado(), request.retirado(), request.justificativa(),
                UUID.fromString(jwt.getSubject())), autenticacao);
    }

    @PostMapping("/{dia}/reabrir")
    @PreAuthorize("hasRole('ADMIN')")
    CaixaResponse reabrir(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dia,
                          @RequestBody MotivoRequest request, @AuthenticationPrincipal Jwt jwt) {
        return CaixaResponse.completo(service.reabrir(dia, request.motivo(), UUID.fromString(jwt.getSubject())), true);
    }

    /**
     * Contagem às cegas: com o caixa aberto, a recepção não vê o dinheiro nem os fechamentos. O
     * troco esperado na abertura, nunca: ele é a contagem do fechamento anterior menos o retirado.
     */
    private static CaixaResponse visao(Consulta consulta, Authentication autenticacao) {
        boolean recepcao = ehRecepcao(autenticacao);
        return recepcao && consulta.caixa().contagemAsCegas()
                ? CaixaResponse.asCegas(consulta) : CaixaResponse.completo(consulta, !recepcao);
    }

    private static boolean ehRecepcao(Authentication autenticacao) {
        return autenticacao.getAuthorities().stream().anyMatch(a -> "ROLE_RECEPCAO".equals(a.getAuthority()));
    }

    record AberturaRequest(@NotNull(message = "é obrigatório") BigDecimal troco) {
    }

    /** {@code retirado}: o dinheiro que sai da gaveta depois de contar; sem ele, zero. */
    record FechamentoRequest(@NotNull(message = "é obrigatório") BigDecimal contado, BigDecimal retirado,
                             String justificativa) {
    }

    record MotivoRequest(String motivo) {
    }

    /**
     * {@code dinheiroOculto}: a visão da recepção com o caixa aberto (contagem às cegas), sem
     * a forma DINHEIRO no movimento nem os fechamentos anteriores. O total soma só o que aparece.
     * {@code trocoEsperado} e {@code diferencaNaAbertura}: só para o ADMIN (ADR 0046, item 8);
     * nulos para a recepção e quando não havia caixa anterior.
     */
    record CaixaResponse(UUID id, LocalDate dia, BigDecimal troco, BigDecimal trocoEsperado,
                         BigDecimal diferencaNaAbertura, Caixa.Situacao situacao, Instant abertoEm,
                         UUID abertoPor, boolean dinheiroOculto, MovimentoResponse movimento,
                         List<FechamentoResponse> fechamentos, List<ReaberturaResponse> reaberturas) {

        static CaixaResponse completo(Consulta consulta, boolean comAbertura) {
            return de(consulta, false, comAbertura, consulta.movimento().doBalcao(), consulta.fechamentos());
        }

        static CaixaResponse asCegas(Consulta consulta) {
            return de(consulta, true, false, consulta.movimento().semDinheiro(), List.of());
        }

        private static CaixaResponse de(Consulta consulta, boolean dinheiroOculto, boolean comAbertura,
                                        Map<MeioDePagamento, Movimento> formas, List<Fechamento> fechamentos) {
            Caixa c = consulta.caixa();
            return new CaixaResponse(c.getId(), c.getDia(), c.getTroco(), comAbertura ? c.getTrocoEsperado() : null,
                    comAbertura ? c.diferencaNaAbertura() : null, c.getSituacao(), c.getAbertoEm(),
                    c.getAbertoPor(), dinheiroOculto, MovimentoResponse.de(formas),
                    fechamentos.stream().map(FechamentoResponse::de).toList(),
                    consulta.reaberturas().stream().map(ReaberturaResponse::de).toList());
        }
    }

    /** As formas que aparecem, na ordem, e o total delas. */
    record MovimentoResponse(List<FormaResponse> formas, TotalResponse total) {
        static MovimentoResponse de(Map<MeioDePagamento, Movimento> formas) {
            Movimento total = formas.values().stream().reduce(Movimento.ZERO, Movimento::mais);
            return new MovimentoResponse(formas.entrySet().stream()
                    .map(e -> FormaResponse.de(e.getKey(), e.getValue())).toList(), TotalResponse.de(total));
        }
    }

    record FormaResponse(MeioDePagamento forma, BigDecimal recebido, int pagamentos, BigDecimal estornado, int estornos,
                         BigDecimal liquido) {
        static FormaResponse de(MeioDePagamento forma, Movimento m) {
            return new FormaResponse(forma, m.recebido(), m.pagamentos(), m.estornado(), m.estornos(), m.liquido());
        }
    }

    record TotalResponse(BigDecimal recebido, int pagamentos, BigDecimal estornado, int estornos, BigDecimal liquido) {
        static TotalResponse de(Movimento m) {
            return new TotalResponse(m.recebido(), m.pagamentos(), m.estornado(), m.estornos(), m.liquido());
        }
    }

    record CaixaAbertoResponse(LocalDate dia, Instant abertoEm, UUID abertoPor) {
        static CaixaAbertoResponse de(Caixa c) {
            return new CaixaAbertoResponse(c.getDia(), c.getAbertoEm(), c.getAbertoPor());
        }
    }

    /**
     * {@code diferencaDosFechados}: a soma da diferença do último fechamento de cada dia fechado;
     * {@code diferencaDasAberturas}: a soma das diferenças entre o troco e o que tinha ficado.
     */
    record RelatorioResponse(LocalDate de, LocalDate ate, List<DiaResponse> dias, MovimentoResponse total,
                             BigDecimal diferencaDosFechados, BigDecimal diferencaDasAberturas) {
        static RelatorioResponse de(Relatorio r) {
            return new RelatorioResponse(r.de(), r.ate(), r.dias().stream().map(DiaResponse::de).toList(),
                    MovimentoResponse.de(r.total().porForma()), r.diferencaDosFechados(), r.diferencaDasAberturas());
        }
    }

    /** {@code situacao} nula: dia de antes do caixa, com lançamentos registrados sem ele. */
    record DiaResponse(LocalDate dia, Caixa.Situacao situacao, BigDecimal troco, BigDecimal trocoEsperado,
                       BigDecimal diferencaNaAbertura, UUID abertoPor, MovimentoResponse movimento,
                       List<FechamentoResponse> fechamentos, List<ReaberturaResponse> reaberturas) {
        static DiaResponse de(DiaDoRelatorio d) {
            Caixa c = d.caixa();
            return new DiaResponse(d.dia(), c == null ? null : c.getSituacao(), c == null ? null : c.getTroco(),
                    c == null ? null : c.getTrocoEsperado(), c == null ? null : c.diferencaNaAbertura(),
                    c == null ? null : c.getAbertoPor(), MovimentoResponse.de(d.movimento().porForma()),
                    d.fechamentos().stream().map(FechamentoResponse::de).toList(),
                    d.reaberturas().stream().map(ReaberturaResponse::de).toList());
        }
    }

    record FechamentoResponse(BigDecimal contado, BigDecimal retirado, BigDecimal esperado, BigDecimal diferenca,
                              String justificativa, Instant em, UUID por) {
        static FechamentoResponse de(Fechamento f) {
            return new FechamentoResponse(f.contado(), f.retirado(), f.esperado(), f.diferenca(), f.justificativa(),
                    f.em(), f.por());
        }
    }

    record ReaberturaResponse(String motivo, Instant em, UUID por) {
        static ReaberturaResponse de(Reabertura r) {
            return new ReaberturaResponse(r.motivo(), r.em(), r.por());
        }
    }
}
