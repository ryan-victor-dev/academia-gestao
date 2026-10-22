package br.com.academiagestao.planos;

import br.com.academiagestao.compartilhado.CondicoesDoPlano;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Planos da academia. Só o ADMIN altera: preço é decisão do dono ou do gerente. */
@RestController
@RequestMapping("/api/planos")
class PlanoController {

    private final PlanoService service;

    PlanoController(PlanoService service) {
        this.service = service;
    }

    /**
     * Lista em ordem de nome. Por padrão só os ativos (os que podem ser contratados).
     * O aluno vê só os ativos, mesmo pedindo os inativos, e numa versão resumida.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR', 'ALUNO')")
    List<? extends VisaoDoPlano> listar(Authentication autenticacao,
                                        @RequestParam(defaultValue = "false") boolean incluirInativos) {
        if (ehAluno(autenticacao)) {
            return service.listar(false).stream().map(PlanoParaAluno::de).toList();
        }
        return service.listar(incluirInativos).stream().map(PlanoResponse::de).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR', 'ALUNO')")
    ResponseEntity<? extends VisaoDoPlano> buscar(Authentication autenticacao, @PathVariable UUID id) {
        Plano plano = service.buscar(id);
        if (ehAluno(autenticacao)) {
            // 404, e não 403: para o aluno, um plano inativo simplesmente não existe.
            if (!plano.isAtivo()) {
                throw new PlanoNaoEncontradoException(id);
            }
            return ResponseEntity.ok(PlanoParaAluno.de(plano));
        }
        return comEtag(plano);
    }

    private static boolean ehAluno(Authentication autenticacao) {
        return autenticacao.getAuthorities().stream().anyMatch(a -> "ROLE_ALUNO".equals(a.getAuthority()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PlanoResponse> cadastrar(@Valid @RequestBody PlanoRequest request) {
        Plano plano = service.cadastrar(request.paraDados());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(plano.getId()).toUri();
        return ResponseEntity.created(location).eTag(VersaoEsperada.etag(plano.getVersao()))
                .body(PlanoResponse.de(plano));
    }

    /**
     * Substitui nome, valor e duração. Exige If-Match com o ETag do GET. Reajuste de valor
     * vale para quem se matricular depois: cada matrícula guarda o valor do seu dia.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PlanoResponse> atualizar(@PathVariable UUID id,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            @Valid @RequestBody PlanoRequest request) {
        return comEtag(service.atualizar(id, request.paraDados(), ifMatch));
    }

    @PostMapping("/{id}/inativar")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PlanoResponse> inativar(@PathVariable UUID id) {
        return comEtag(service.inativar(id));
    }

    @PostMapping("/{id}/reativar")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PlanoResponse> reativar(@PathVariable UUID id) {
        return comEtag(service.reativar(id));
    }

    private static ResponseEntity<PlanoResponse> comEtag(Plano plano) {
        return ResponseEntity.ok().eTag(VersaoEsperada.etag(plano.getVersao())).body(PlanoResponse.de(plano));
    }

    record PlanoRequest(
            @NotBlank(message = "é obrigatório")
            @Size(max = CondicoesDoPlano.TAMANHO_MAXIMO_NOME, message = "pode ter no máximo {max} caracteres")
            String nome,

            @NotNull(message = "é obrigatório")
            @Positive(message = "precisa ser maior que zero")
            @DecimalMax(value = CondicoesDoPlano.VALOR_MAXIMO_TEXTO, message = "pode ser no máximo {value}")
            @Digits(integer = 6, fraction = 2, message = "até 6 dígitos e 2 casas decimais")
            BigDecimal valorMensal,

            @NotNull(message = "é obrigatório")
            @Min(value = 1, message = "precisa ser de pelo menos {value} mês")
            @Max(value = CondicoesDoPlano.DURACAO_MAXIMA_MESES, message = "pode ser no máximo {value} meses")
            Integer duracaoMeses,

            // Ausente: sem fidelidade. Com fidelidade, o percentual é obrigatório (ADR 0018).
            Boolean fidelidade,

            @DecimalMin(value = "0.01", message = "precisa ser de pelo menos {value}%")
            @DecimalMax(value = CondicoesDoPlano.PERCENTUAL_MAXIMO_MULTA_TEXTO, message = "pode ser no máximo {value}%")
            @Digits(integer = 3, fraction = 2, message = "até 2 casas decimais")
            BigDecimal percentualMulta,

            // Ausente: mensal.
            FormaPagamento formaPagamento) {

        DadosPlano paraDados() {
            return new DadosPlano(nome, valorMensal, duracaoMeses, fidelidade, percentualMulta, formaPagamento);
        }
    }

    /** O que a API mostra de um plano; o formato depende de quem pergunta. */
    sealed interface VisaoDoPlano permits PlanoResponse, PlanoParaAluno {
    }

    /**
     * Visão do aluno: só o que interessa para escolher ou renovar o plano, inclusive a
     * fidelidade e a multa, que ele precisa conhecer antes de contratar.
     */
    record PlanoParaAluno(UUID id, String nome, BigDecimal valorMensal, int duracaoMeses, BigDecimal valorTotal,
                          boolean fidelidade, BigDecimal percentualMulta, FormaPagamento formaPagamento)
            implements VisaoDoPlano {

        static PlanoParaAluno de(Plano p) {
            return new PlanoParaAluno(p.getId(), p.getNome(), p.getValorMensal(), p.getDuracaoMeses(),
                    p.getValorTotal(), p.isFidelidade(), p.getPercentualMulta(), p.getFormaPagamento());
        }
    }

    record PlanoResponse(UUID id, String nome, BigDecimal valorMensal, int duracaoMeses, BigDecimal valorTotal,
                         boolean fidelidade, BigDecimal percentualMulta, FormaPagamento formaPagamento,
                         boolean ativo, Instant criadoEm) implements VisaoDoPlano {

        static PlanoResponse de(Plano p) {
            return new PlanoResponse(p.getId(), p.getNome(), p.getValorMensal(), p.getDuracaoMeses(),
                    p.getValorTotal(), p.isFidelidade(), p.getPercentualMulta(), p.getFormaPagamento(),
                    p.isAtivo(), p.getCriadoEm());
        }
    }
}
