package br.com.academiagestao.treinos;

import br.com.academiagestao.auth.ClaimsDoToken;
import br.com.academiagestao.compartilhado.Pagina;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Fichas de treino (ADR 0027): o instrutor (e o ADMIN) monta e corrige; a recepção só vê,
 * para tirar dúvida do aluno no balcão; o aluno vê as dele (/me).
 */
@RestController
@RequestMapping("/api/fichas")
class FichaController {

    // Cada ficha vai inteira (até 7 divisões de 20 exercícios): 20 por página ainda é pouco.
    static final int MAXIMO_FICHAS_POR_PAGINA = Ficha.MAXIMO_FICHAS_NUMA_CONSULTA;

    private final FichaService service;

    FichaController(FichaService service) {
        this.service = service;
    }

    /** Monta a ficha nova do aluno; a atual, se houver, vai para o histórico. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUTOR')")
    ResponseEntity<FichaResponse> montar(@Valid @RequestBody MontagemRequest request, @AuthenticationPrincipal Jwt jwt) {
        Ficha ficha = service.montar(request.alunoId(), request.paraDados(), UUID.fromString(jwt.getSubject()));
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(ficha.getId()).toUri();
        return ResponseEntity.created(location).eTag(VersaoEsperada.etag(ficha.getVersao()))
                .body(FichaResponse.de(ficha, service.hoje()));
    }

    /** Corrige a ficha atual (subir a carga, trocar um exercício): troca as divisões inteiras. Exige If-Match. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUTOR')")
    ResponseEntity<FichaResponse> corrigir(@PathVariable UUID id,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @Valid @RequestBody CorrecaoRequest request) {
        return comEtag(service.corrigir(id, request.paraDados(), ifMatch));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUTOR', 'RECEPCAO')")
    ResponseEntity<FichaResponse> buscar(@PathVariable UUID id) {
        return comEtag(service.buscar(id));
    }

    /** A ficha atual do aluno; 404 se ele não tem. */
    @GetMapping("/atual")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUTOR', 'RECEPCAO')")
    ResponseEntity<FichaResponse> atual(@RequestParam UUID alunoId) {
        return comEtag(service.atual(alunoId));
    }

    /**
     * Histórico do aluno: a atual e as encerradas, da mais nova para a mais antiga, em páginas
     * (cada ficha vem inteira, então a página é pequena).
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUTOR', 'RECEPCAO')")
    Pagina<FichaResponse> historico(
            @RequestParam UUID alunoId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = MAXIMO_FICHAS_POR_PAGINA, message = "pode ser no máximo {value}") int tamanho) {
        LocalDate hoje = service.hoje();
        return Pagina.de(service.historico(alunoId, pagina, tamanho), f -> FichaResponse.de(f, hoje));
    }

    /**
     * As fichas do próprio aluno, a atual primeiro, mesmo inativo (continua vendo as antigas).
     * O id vem do token, nunca da URL: não há como pedir as fichas de outra pessoa.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ALUNO')")
    Pagina<FichaParaAluno> minhas(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = MAXIMO_FICHAS_POR_PAGINA, message = "pode ser no máximo {value}") int tamanho) {
        String alunoId = jwt.getClaimAsString(ClaimsDoToken.ALUNO_ID);
        if (alunoId == null) {
            throw new AccessDeniedException("Token de aluno sem alunoId");
        }
        return Pagina.de(service.historico(UUID.fromString(alunoId), pagina, tamanho), FichaParaAluno::de);
    }

    /** Fichas atuais com a revisão vencida (até hoje), da mais antiga; só alunos ativos. */
    @GetMapping("/revisoes-vencidas")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUTOR')")
    Pagina<RevisaoVencidaResponse> revisoesVencidas(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
        Pagina<FichaService.RevisaoPendente> vencidas = service.revisoesVencidas(pagina, tamanho);
        return new Pagina<>(vencidas.conteudo().stream().map(RevisaoVencidaResponse::de).toList(), vencidas.pagina(),
                vencidas.tamanho(), vencidas.totalElementos(), vencidas.totalPaginas());
    }

    private ResponseEntity<FichaResponse> comEtag(Ficha ficha) {
        return ResponseEntity.ok().eTag(VersaoEsperada.etag(ficha.getVersao()))
                .body(FichaResponse.de(ficha, service.hoje()));
    }

    record MontagemRequest(
            @NotNull(message = "é obrigatório")
            UUID alunoId,

            LocalDate revisaoEm,

            @NotEmpty(message = "a ficha precisa de pelo menos uma divisão")
            @Size(max = Ficha.MAXIMO_DIVISOES, message = "pode ter no máximo {max} divisões")
            List<@Valid @NotNull(message = "é obrigatório") DivisaoRequest> divisoes) {

        DadosFicha paraDados() {
            return new DadosFicha(revisaoEm, DivisaoRequest.paraDados(divisoes));
        }
    }

    record CorrecaoRequest(
            LocalDate revisaoEm,

            @NotEmpty(message = "a ficha precisa de pelo menos uma divisão")
            @Size(max = Ficha.MAXIMO_DIVISOES, message = "pode ter no máximo {max} divisões")
            List<@Valid @NotNull(message = "é obrigatório") DivisaoRequest> divisoes) {

        DadosFicha paraDados() {
            return new DadosFicha(revisaoEm, DivisaoRequest.paraDados(divisoes));
        }
    }

    record DivisaoRequest(
            @NotBlank(message = "é obrigatório")
            @Size(max = Ficha.TAMANHO_MAXIMO_NOME_DIVISAO, message = "pode ter no máximo {max} caracteres")
            String nome,

            @NotEmpty(message = "a divisão precisa de pelo menos um exercício")
            @Size(max = Ficha.MAXIMO_EXERCICIOS_POR_DIVISAO, message = "pode ter no máximo {max} exercícios")
            List<@Valid @NotNull(message = "é obrigatório") ItemRequest> exercicios) {

        static List<DadosFicha.Divisao> paraDados(List<DivisaoRequest> divisoes) {
            return divisoes.stream()
                    .map(d -> new DadosFicha.Divisao(d.nome(), d.exercicios().stream().map(ItemRequest::paraDados).toList()))
                    .toList();
        }
    }

    record ItemRequest(
            @NotNull(message = "é obrigatório")
            UUID exercicioId,

            @NotNull(message = "é obrigatório")
            @Min(value = 1, message = "deve ser de 1 a " + Ficha.MAXIMO_SERIES)
            @Max(value = Ficha.MAXIMO_SERIES, message = "deve ser de 1 a " + Ficha.MAXIMO_SERIES)
            Integer series,

            @NotBlank(message = "é obrigatório")
            @Size(max = Ficha.TAMANHO_MAXIMO_REPETICOES, message = "pode ter no máximo {max} caracteres")
            String repeticoes,

            @Size(max = Ficha.TAMANHO_MAXIMO_CARGA, message = "pode ter no máximo {max} caracteres")
            String carga,

            @Min(value = 0, message = "deve ser de 0 a " + Ficha.MAXIMO_DESCANSO_SEGUNDOS + " segundos")
            @Max(value = Ficha.MAXIMO_DESCANSO_SEGUNDOS,
                    message = "deve ser de 0 a " + Ficha.MAXIMO_DESCANSO_SEGUNDOS + " segundos")
            Integer descansoSegundos,

            @Size(max = Ficha.TAMANHO_MAXIMO_OBSERVACAO, message = "pode ter no máximo {max} caracteres")
            String observacao) {

        DadosFicha.Item paraDados() {
            return new DadosFicha.Item(exercicioId, series, repeticoes, carga, descansoSegundos, observacao);
        }
    }

    /**
     * {@code atual} falso = encerrada (no histórico). {@code revisaoVencida}: a data de revisão
     * chegou e a ficha ainda é a atual; ela continua valendo.
     */
    record FichaResponse(UUID id, UUID alunoId, boolean atual, Instant montadaEm, UUID montadaPor,
                         LocalDate revisaoEm, boolean revisaoVencida, Instant corrigidaEm, Instant encerradaEm,
                         List<DivisaoResponse> divisoes) {

        static FichaResponse de(Ficha f, LocalDate hoje) {
            return new FichaResponse(f.getId(), f.getAlunoId(), f.isAtual(), f.getMontadaEm(), f.getMontadaPor(),
                    f.getRevisaoEm(), f.revisaoVencida(hoje), f.getCorrigidaEm(), f.getEncerradaEm(),
                    f.getDivisoes().stream().map(DivisaoResponse::de).toList());
        }
    }

    /**
     * O que o aluno vê da ficha: sem quem da equipe montou (dado interno) e sem o aviso de
     * revisão vencida, que é tarefa do instrutor.
     */
    record FichaParaAluno(UUID id, boolean atual, Instant montadaEm, LocalDate revisaoEm, Instant corrigidaEm,
                          Instant encerradaEm, List<DivisaoResponse> divisoes) {

        static FichaParaAluno de(Ficha f) {
            return new FichaParaAluno(f.getId(), f.isAtual(), f.getMontadaEm(), f.getRevisaoEm(), f.getCorrigidaEm(),
                    f.getEncerradaEm(), f.getDivisoes().stream().map(DivisaoResponse::de).toList());
        }
    }

    record RevisaoVencidaResponse(UUID fichaId, UUID alunoId, String alunoNome, LocalDate revisaoEm,
                                  Instant montadaEm, UUID montadaPor) {

        static RevisaoVencidaResponse de(FichaService.RevisaoPendente r) {
            var f = r.ficha();
            return new RevisaoVencidaResponse(f.fichaId(), f.alunoId(), r.alunoNome(), f.revisaoEm(), f.montadaEm(),
                    f.montadaPor());
        }
    }

    record DivisaoResponse(String nome, List<ItemResponse> exercicios) {

        static DivisaoResponse de(DivisaoDaFicha d) {
            return new DivisaoResponse(d.getNome(), d.getExercicios().stream().map(ItemResponse::de).toList());
        }
    }

    record ItemResponse(UUID exercicioId, String nome, int series, String repeticoes, String carga,
                        Integer descansoSegundos, String observacao) {

        static ItemResponse de(ExercicioDaFicha e) {
            return new ItemResponse(e.getExercicioId(), e.getNome(), e.getSeries(), e.getRepeticoes(), e.getCarga(),
                    e.getDescansoSegundos(), e.getObservacao());
        }
    }
}
