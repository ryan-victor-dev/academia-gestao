package br.com.academiagestao.matriculas;

import br.com.academiagestao.auth.ClaimsDoToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Matrículas (ADR 0016). A recepção matricula e cancela; o valor vem sempre do plano. */
@RestController
@RequestMapping("/api/matriculas")
class MatriculaController {

    private final MatriculaService service;

    MatriculaController(MatriculaService service) {
        this.service = service;
    }

    /** Sem {@code inicio}, começa hoje. O valor e a duração são os do plano neste momento. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<MatriculaResponse> matricular(@Valid @RequestBody MatriculaRequest request) {
        Matricula matricula = service.matricular(request.alunoId(), request.planoId(), request.inicio());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(matricula.getId()).toUri();
        return ResponseEntity.created(location).body(MatriculaResponse.de(matricula, service.agora()));
    }

    /**
     * As matrículas do próprio aluno, mesmo inativo (é direito dele, LGPD art. 18). O id vem
     * do token, nunca da URL: não há como pedir as matrículas de outra pessoa.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ALUNO')")
    List<MatriculaParaAluno> minhas(@AuthenticationPrincipal Jwt jwt) {
        String alunoId = jwt.getClaimAsString(ClaimsDoToken.ALUNO_ID);
        if (alunoId == null) {
            throw new AccessDeniedException("Token de aluno sem alunoId");
        }
        Instant agora = service.agora();
        return service.listarDoAluno(UUID.fromString(alunoId)).stream()
                .map(m -> MatriculaParaAluno.de(m, agora)).toList();
    }

    /**
     * Se o aluno pode treinar hoje. É tudo o que o instrutor vê de matrícula: valores e
     * histórico não são da conta dele (LGPD, minimização). Aluno inexistente: false.
     */
    @GetMapping("/vigente")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR')")
    PodeTreinar vigente(@RequestParam UUID alunoId) {
        return new PodeTreinar(alunoId, service.podeTreinar(alunoId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    MatriculaResponse buscar(@PathVariable UUID id) {
        return MatriculaResponse.de(service.buscar(id), service.agora());
    }

    /** Histórico do aluno, da mais recente para a mais antiga, inclusive as canceladas. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    List<MatriculaResponse> listarDoAluno(@RequestParam UUID alunoId) {
        Instant agora = service.agora();
        return service.listarDoAluno(alunoId).stream().map(m -> MatriculaResponse.de(m, agora)).toList();
    }

    /** Só agendada ou vigente. Fica gravado quem cancelou, quando e por quê. */
    @PostMapping("/{id}/cancelar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    MatriculaResponse cancelar(@PathVariable UUID id, @Valid @RequestBody CancelamentoRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        Matricula matricula = service.cancelar(id, request.motivo(), UUID.fromString(jwt.getSubject()));
        return MatriculaResponse.de(matricula, service.agora());
    }

    /** Pausa a matrícula; o fim avança pelos dias parados. Sem {@code inicio}, começa hoje. */
    @PostMapping("/{id}/trancar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    MatriculaResponse trancar(@PathVariable UUID id, @Valid @RequestBody TrancamentoRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        Matricula matricula = service.trancar(id, request.inicio(), request.voltaPrevista(), request.motivo(),
                UUID.fromString(jwt.getSubject()));
        return MatriculaResponse.de(matricula, service.agora());
    }

    /** O aluno voltou antes do previsto: contam só os dias parados. */
    @PostMapping("/{id}/registrar-volta")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    MatriculaResponse registrarVolta(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return MatriculaResponse.de(service.registrarVolta(id, UUID.fromString(jwt.getSubject())), service.agora());
    }

    /** Troca imediata: a atual termina na véspera, e a nova (devolvida aqui) começa hoje. */
    @PostMapping("/{id}/trocar-plano")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<MatriculaResponse> trocarPlano(@PathVariable UUID id, @Valid @RequestBody TrocaRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        Matricula nova = service.trocarPlano(id, request.planoId(), UUID.fromString(jwt.getSubject()));
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/matriculas/{id}").buildAndExpand(nova.getId()).toUri();
        return ResponseEntity.created(location).body(MatriculaResponse.de(nova, service.agora()));
    }

    record TrocaRequest(
            @NotNull(message = "é obrigatório")
            UUID planoId) {
    }

    record TrancamentoRequest(
            LocalDate inicio,

            @NotNull(message = "é obrigatória")
            LocalDate voltaPrevista,

            @NotBlank(message = "é obrigatório")
            @Size(max = Matricula.TAMANHO_MAXIMO_MOTIVO, message = "pode ter no máximo {max} caracteres")
            String motivo) {
    }

    record TrancamentoResponse(LocalDate inicio, LocalDate voltaPrevista, LocalDate volta, int dias, String motivo) {

        static List<TrancamentoResponse> de(Matricula m) {
            return m.getTrancamentos().stream()
                    .map(t -> new TrancamentoResponse(t.getInicio(), t.getVoltaPrevista(), t.getVolta(), t.dias(),
                            t.getMotivo()))
                    .toList();
        }
    }

    record MatriculaRequest(
            @NotNull(message = "é obrigatório")
            UUID alunoId,

            @NotNull(message = "é obrigatório")
            UUID planoId,

            LocalDate inicio) {
    }

    record CancelamentoRequest(
            @NotBlank(message = "é obrigatório")
            @Size(max = Matricula.TAMANHO_MAXIMO_MOTIVO, message = "pode ter no máximo {max} caracteres")
            String motivo) {
    }

    /**
     * Um sim ou não só: o instrutor não fica sabendo se o "não" é falta de matrícula ou
     * pagamento atrasado (LGPD, minimização).
     */
    record PodeTreinar(UUID alunoId, boolean podeTreinar) {
    }

    /** Visão do aluno: o contrato dele, sem o id de quem da equipe cancelou. */
    record MatriculaParaAluno(UUID id, UUID planoId, String planoNome, BigDecimal valorMensal, int duracaoMeses,
                              BigDecimal valorTotal, boolean fidelidade, BigDecimal percentualMulta,
                              FormaDePagamento formaPagamento, LocalDate inicio, LocalDate primeiroVencimento,
                              LocalDate fim, BigDecimal credito, int diasTrancados,
                              List<TrancamentoResponse> trancamentos,
                              SituacaoMatricula situacao, Instant canceladaEm, String motivoCancelamento,
                              LocalDate trocadaEm, UUID trocaDe, BigDecimal multa) {

        static MatriculaParaAluno de(Matricula m, Instant agora) {
            return new MatriculaParaAluno(m.getId(), m.getPlanoId(), m.getPlanoNome(), m.getValorMensal(),
                    m.getDuracaoMeses(), m.getValorTotal(), m.isFidelidade(), m.getPercentualMulta(),
                    m.getFormaPagamento(), m.getInicio(), m.getPrimeiroVencimento(), m.getFim(), m.getCredito(),
                    m.getDiasTrancados(), TrancamentoResponse.de(m), m.situacao(agora), m.getCanceladaEm(),
                    m.getMotivoCancelamento(), m.getTrocadaEm(), m.getTrocaDe(), m.getMulta());
        }
    }

    record MatriculaResponse(UUID id, UUID alunoId, UUID planoId, String planoNome, BigDecimal valorMensal,
                             int duracaoMeses, BigDecimal valorTotal, boolean fidelidade, BigDecimal percentualMulta,
                             FormaDePagamento formaPagamento, LocalDate inicio, LocalDate primeiroVencimento,
                             LocalDate fim, BigDecimal credito, int diasTrancados,
                             List<TrancamentoResponse> trancamentos, SituacaoMatricula situacao, Instant criadaEm,
                             Instant canceladaEm, UUID canceladaPor, String motivoCancelamento,
                             LocalDate trocadaEm, UUID trocadaPor, UUID trocaDe, BigDecimal multa) {

        static MatriculaResponse de(Matricula m, Instant agora) {
            return new MatriculaResponse(m.getId(), m.getAlunoId(), m.getPlanoId(), m.getPlanoNome(),
                    m.getValorMensal(), m.getDuracaoMeses(), m.getValorTotal(), m.isFidelidade(),
                    m.getPercentualMulta(), m.getFormaPagamento(), m.getInicio(), m.getPrimeiroVencimento(),
                    m.getFim(), m.getCredito(), m.getDiasTrancados(), TrancamentoResponse.de(m), m.situacao(agora),
                    m.getCriadaEm(), m.getCanceladaEm(),
                    m.getCanceladaPor(), m.getMotivoCancelamento(), m.getTrocadaEm(), m.getTrocadaPor(),
                    m.getTrocaDe(), m.getMulta());
        }
    }
}
