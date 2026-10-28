package br.com.academiagestao.pagamentos;

import br.com.academiagestao.alunos.AlunosParaOutrosModulos;
import br.com.academiagestao.auth.ClaimsDoToken;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.CobrancaEmAberto;
import br.com.academiagestao.pagamentos.PagamentoOnlineService.Opcoes;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pagamento online (ADR 0034). O aluno vê o que pode pagar e gera o Pix ou o boleto; a empresa
 * avisa quando foi pago; o ADMIN cuida do dinheiro que chegou sem ter o que pagar.
 */
@RestController
@RequestMapping("/api/pagamentos-online")
class PagamentoOnlineController {

    private final PagamentoOnlineService service;
    private final ConfirmacaoDePagamentoOnlineService confirmacao;
    private final DevolucaoDePagamentoOnlineService devolucao;
    private final AlunosParaOutrosModulos alunos;

    PagamentoOnlineController(PagamentoOnlineService service, ConfirmacaoDePagamentoOnlineService confirmacao,
                              DevolucaoDePagamentoOnlineService devolucao, AlunosParaOutrosModulos alunos) {
        this.service = service;
        this.confirmacao = confirmacao;
        this.devolucao = devolucao;
        this.alunos = alunos;
    }

    /** O que o aluno pode pagar hoje e como. O id vem do token, nunca da URL. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ALUNO')")
    ResponseEntity<OpcoesResponse> opcoes(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(OpcoesResponse.de(service.opcoes(alunoId(jwt))));
    }

    /** Gera o Pix ou o boleto (ou devolve o que ainda vale). Sem cache: o copia e cola é de quem pediu. */
    @PostMapping("/me")
    @PreAuthorize("hasRole('ALUNO')")
    ResponseEntity<PagamentoOnlineResponse> gerar(@Valid @RequestBody PedidoRequest pedido,
                                                  @AuthenticationPrincipal Jwt jwt) {
        PagamentoOnline gerado = service.gerar(alunoId(jwt), UUID.fromString(jwt.getSubject()), pedido.cobrancaId(),
                pedido.forma());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(PagamentoOnlineResponse.de(gerado));
    }

    /** Só com o provedor simulado (desenvolvimento e testes). */
    @PostMapping("/me/{id}/simular-pagamento")
    @PreAuthorize("hasRole('ALUNO')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void simular(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        confirmacao.simularPagamento(alunoId(jwt), id);
    }

    /**
     * O aviso da empresa: sem login (quem chama é a empresa). A empresa confere a autenticidade
     * do aviso, e o sistema consulta cada pagamento citado: o corpo nunca é a fonte (ADR 0034).
     */
    @PostMapping("/aviso")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void aviso(HttpServletRequest requisicao, @RequestBody(required = false) byte[] corpo) {
        Map<String, String> cabecalhos = new HashMap<>();
        for (String nome : Collections.list(requisicao.getHeaderNames())) {
            cabecalhos.put(nome.toLowerCase(Locale.ROOT), requisicao.getHeader(nome));
        }
        confirmacao.receberAviso(cabecalhos, corpo == null ? new byte[0] : corpo);
    }

    /** Com o nome do aluno: o ADMIN acha o pagamento no painel da empresa e devolve. */
    @GetMapping("/a-devolver")
    @PreAuthorize("hasRole('ADMIN')")
    List<ADevolverResponse> aDevolver() {
        List<PagamentoOnline> lista = devolucao.aDevolver();
        Map<UUID, String> nomes = alunos.nomes(lista.stream().map(PagamentoOnline::getAlunoId).collect(Collectors.toSet()));
        return lista.stream().map(p -> new ADevolverResponse(PagamentoOnlineResponse.de(p), nomes.get(p.getAlunoId()),
                devolucao.devolvePeloSistema(p))).toList();
    }

    /** O ADMIN confirma, e o sistema devolve pela empresa (ADR 0035). */
    @PostMapping("/{id}/devolver")
    @PreAuthorize("hasRole('ADMIN')")
    PagamentoOnlineResponse devolver(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return PagamentoOnlineResponse.de(devolucao.devolverPelaEmpresa(id, UUID.fromString(jwt.getSubject())));
    }

    /** As contestações dos últimos 90 dias (ADR 0037): o ADMIN é avisado aqui. */
    @GetMapping("/contestacoes")
    @PreAuthorize("hasRole('ADMIN')")
    List<ContestacaoResponse> contestacoes() {
        List<PagamentoOnline> lista = confirmacao.contestacoes();
        Map<UUID, String> nomes = alunos.nomes(lista.stream().map(PagamentoOnline::getAlunoId).collect(Collectors.toSet()));
        return lista.stream().map(p -> new ContestacaoResponse(PagamentoOnlineResponse.de(p), nomes.get(p.getAlunoId())))
                .toList();
    }

    /** Só com o provedor simulado (desenvolvimento e testes): o pagador "contesta" no banco. */
    @PostMapping("/{id}/simular-contestacao")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void simularContestacao(@PathVariable UUID id) {
        confirmacao.simularContestacao(id);
    }

    /** Devolvido por fora (a empresa não devolve boleto pelo sistema, por exemplo). */
    @PostMapping("/{id}/devolvido")
    @PreAuthorize("hasRole('ADMIN')")
    PagamentoOnlineResponse devolvido(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return PagamentoOnlineResponse.de(devolucao.marcarDevolvido(id, UUID.fromString(jwt.getSubject())));
    }

    private static UUID alunoId(Jwt jwt) {
        String alunoId = jwt.getClaimAsString(ClaimsDoToken.ALUNO_ID);
        if (alunoId == null) {
            throw new AccessDeniedException("Token de aluno sem alunoId");
        }
        return UUID.fromString(alunoId);
    }

    record PedidoRequest(@NotNull(message = "é obrigatório") UUID cobrancaId,
                         @NotNull(message = "é obrigatório") Forma forma) {
    }

    record PagamentoOnlineResponse(UUID id, UUID cobrancaId, UUID alunoId, Forma forma, BigDecimal valor,
                                   BigDecimal acrescimo, BigDecimal juros, BigDecimal total, String pixCopiaECola,
                                   String boletoLinhaDigitavel, String boletoUrl, String linkDePagamento,
                                   Instant validoAte, Instant geradoEm, PagamentoOnline.Situacao situacao, BigDecimal recebido,
                                   Instant recebidoEm, String motivoDaDevolucao, BigDecimal valorADevolver,
                                   boolean pagouACobranca, Instant devolvidoEm, UUID devolvidoPor,
                                   Instant contestadoEm, Boolean cobrancaReaberta) {
        static PagamentoOnlineResponse de(PagamentoOnline p) {
            return new PagamentoOnlineResponse(p.getId(), p.getCobrancaId(), p.getAlunoId(), p.getForma(),
                    p.getValor(), p.getAcrescimo(), p.getJuros(), p.total(), p.getPixCopiaECola(),
                    p.getBoletoLinhaDigitavel(), p.getBoletoUrl(), p.getLinkDePagamento(), p.getValidoAte(),
                    p.getGeradoEm(),
                    p.getSituacao(), p.getRecebido(), p.getRecebidoEm(), p.getMotivoDaDevolucao(),
                    p.getValorADevolver(), p.pagouACobranca(), p.getDevolvidoEm(), p.getDevolvidoPor(),
                    p.getContestadoEm(), p.getCobrancaReaberta());
        }
    }

    record ADevolverResponse(@JsonUnwrapped PagamentoOnlineResponse pagamento, String alunoNome,
                             boolean devolvePeloSistema) {
    }

    record ContestacaoResponse(@JsonUnwrapped PagamentoOnlineResponse pagamento, String alunoNome) {
    }

    record PagavelResponse(UUID cobrancaId, String tipo, int parcela, LocalDate vencimento, BigDecimal valor,
                           BigDecimal acrescimo, BigDecimal juros, BigDecimal total, long diasDeAtraso,
                           PagamentoOnlineResponse pix, PagamentoOnlineResponse boleto,
                           PagamentoOnlineResponse credito, PagamentoOnlineResponse debito) {
        static PagavelResponse de(PagamentoOnlineService.Pagavel p) {
            CobrancaEmAberto c = p.cobranca();
            return new PagavelResponse(c.id(), c.tipo(), c.parcela(), c.vencimento(), c.devido().valor(),
                    c.devido().acrescimo(), c.devido().juros(), c.devido().total(), c.diasDeAtraso(),
                    resposta(p.pix()), resposta(p.boleto()), resposta(p.credito()), resposta(p.debito()));
        }

        private static PagamentoOnlineResponse resposta(Optional<PagamentoOnline> p) {
            return p.map(PagamentoOnlineResponse::de).orElse(null);
        }
    }

    record OpcoesResponse(boolean ligado, boolean simulado, boolean pix, boolean boleto, boolean credito,
                          boolean debito, List<PagavelResponse> pagaveis) {
        static OpcoesResponse de(Opcoes o) {
            return new OpcoesResponse(o.ligado(), o.simulado(), o.pix(), o.boleto(), o.credito(), o.debito(),
                    o.pagaveis().stream().map(PagavelResponse::de).toList());
        }
    }
}
