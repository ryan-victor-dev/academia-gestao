package br.com.academiagestao.treinos;

import br.com.academiagestao.compartilhado.VersaoEsperada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.List;
import java.util.UUID;

/** Catálogo de exercícios da academia: os instrutores (e o ADMIN) mantêm (ADR 0027). */
@RestController
@RequestMapping("/api/exercicios")
@PreAuthorize("hasAnyRole('ADMIN', 'INSTRUTOR')")
class ExercicioController {

    private final ExercicioService service;

    ExercicioController(ExercicioService service) {
        this.service = service;
    }

    /** Em ordem de nome, opcionalmente de um grupo. Por padrão só os ativos (os que entram em fichas novas). */
    @GetMapping
    List<ExercicioResponse> listar(@RequestParam(required = false) GrupoMuscular grupoMuscular,
                                   @RequestParam(defaultValue = "false") boolean incluirInativos) {
        return service.listar(grupoMuscular, incluirInativos).stream().map(ExercicioResponse::de).toList();
    }

    @GetMapping("/{id}")
    ResponseEntity<ExercicioResponse> buscar(@PathVariable UUID id) {
        return comEtag(service.buscar(id));
    }

    @PostMapping
    ResponseEntity<ExercicioResponse> cadastrar(@Valid @RequestBody ExercicioRequest request) {
        Exercicio exercicio = service.cadastrar(request.paraDados());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(exercicio.getId()).toUri();
        return ResponseEntity.created(location).eTag(VersaoEsperada.etag(exercicio.getVersao()))
                .body(ExercicioResponse.de(exercicio));
    }

    /** Substitui nome, grupo e observação. Exige If-Match com o ETag do GET. As fichas já montadas não mudam. */
    @PutMapping("/{id}")
    ResponseEntity<ExercicioResponse> atualizar(@PathVariable UUID id,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                                String ifMatch,
                                                @Valid @RequestBody ExercicioRequest request) {
        return comEtag(service.atualizar(id, request.paraDados(), ifMatch));
    }

    @PostMapping("/{id}/inativar")
    ResponseEntity<ExercicioResponse> inativar(@PathVariable UUID id) {
        return comEtag(service.inativar(id));
    }

    @PostMapping("/{id}/reativar")
    ResponseEntity<ExercicioResponse> reativar(@PathVariable UUID id) {
        return comEtag(service.reativar(id));
    }

    /**
     * ADMIN e instrutor (o cadastrado por engano, ADR 0047), e só o exercício que nunca entrou numa
     * ficha (ADR 0043); com fichas, 409: inative.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUTOR')")
    ResponseEntity<Void> excluir(@PathVariable UUID id) {
        service.excluir(id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<ExercicioResponse> comEtag(Exercicio exercicio) {
        return ResponseEntity.ok().eTag(VersaoEsperada.etag(exercicio.getVersao()))
                .body(ExercicioResponse.de(exercicio));
    }

    record ExercicioRequest(
            @NotBlank(message = "é obrigatório")
            @Size(max = Exercicio.TAMANHO_MAXIMO_NOME, message = "pode ter no máximo {max} caracteres")
            String nome,

            @NotNull(message = "é obrigatório")
            GrupoMuscular grupoMuscular,

            @Size(max = Exercicio.TAMANHO_MAXIMO_OBSERVACAO, message = "pode ter no máximo {max} caracteres")
            String observacao) {

        DadosExercicio paraDados() {
            return new DadosExercicio(nome, grupoMuscular, observacao);
        }
    }

    record ExercicioResponse(UUID id, String nome, GrupoMuscular grupoMuscular, String observacao, boolean ativo,
                             Instant criadoEm) {

        static ExercicioResponse de(Exercicio e) {
            return new ExercicioResponse(e.getId(), e.getNome(), e.getGrupoMuscular(), e.getObservacao(),
                    e.isAtivo(), e.getCriadoEm());
        }
    }
}
