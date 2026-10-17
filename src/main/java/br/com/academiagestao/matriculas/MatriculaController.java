package br.com.academiagestao.matriculas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
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

    record MatriculaResponse(UUID id, UUID alunoId, UUID planoId, BigDecimal valorMensal, int duracaoMeses,
                             BigDecimal valorTotal, LocalDate inicio, LocalDate fim, SituacaoMatricula situacao,
                             Instant criadaEm, Instant canceladaEm, UUID canceladaPor, String motivoCancelamento) {

        static MatriculaResponse de(Matricula m, Instant agora) {
            return new MatriculaResponse(m.getId(), m.getAlunoId(), m.getPlanoId(), m.getValorMensal(),
                    m.getDuracaoMeses(), m.getValorTotal(), m.getInicio(), m.getFim(), m.situacao(agora),
                    m.getCriadaEm(), m.getCanceladaEm(), m.getCanceladaPor(), m.getMotivoCancelamento());
        }
    }
}
