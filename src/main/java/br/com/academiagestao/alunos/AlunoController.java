package br.com.academiagestao.alunos;

import br.com.academiagestao.auth.ClaimsDoToken;
import br.com.academiagestao.auth.LoginsDeAlunos;
import br.com.academiagestao.compartilhado.Pagina;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/alunos")
class AlunoController {

    private final AlunoService service;

    AlunoController(AlunoService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<AlunoResponse> cadastrar(@Valid @RequestBody AlunoRequest request) {
        Aluno aluno = service.cadastrar(request.paraDados());
        // 201 Created com o endereço do novo recurso no cabeçalho Location.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(aluno.getId()).toUri();
        return ResponseEntity.created(location).body(AlunoResponse.de(aluno));
    }

    /**
     * Lista paginada, em ordem de nome. Por padrão só alunos ativos.
     * Ex.: GET /api/alunos?busca=ana&pagina=0&tamanho=20
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR')")
    Pagina<AlunoResponse> listar(
            @RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "false") boolean incluirInativos,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
        return Pagina.de(service.listar(busca, incluirInativos, pagina, tamanho), AlunoResponse::de);
    }

    /**
     * O aluno vê o próprio cadastro, mesmo inativo (é direito dele, LGPD art. 18).
     * O id vem do token, nunca da URL: não há como pedir o cadastro de outra pessoa.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ALUNO')")
    AlunoResponse meuCadastro(@AuthenticationPrincipal Jwt jwt) {
        String alunoId = jwt.getClaimAsString(ClaimsDoToken.ALUNO_ID);
        if (alunoId == null) {
            throw new AccessDeniedException("Token de aluno sem alunoId");
        }
        return AlunoResponse.de(service.buscar(UUID.fromString(alunoId)));
    }

    // O instrutor também consulta: precisa ver o aluno para montar a ficha de treino.
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR')")
    AlunoResponse buscar(@PathVariable UUID id) {
        return AlunoResponse.de(service.buscar(id));
    }

    /** Substitui todos os dados editáveis: campo opcional que não vier fica vazio. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    AlunoResponse atualizar(@PathVariable UUID id, @Valid @RequestBody AlunoRequest request) {
        return AlunoResponse.de(service.atualizar(id, request.paraDados()));
    }

    /**
     * Cria o acesso do aluno ao sistema. A resposta traz a senha temporária, que a
     * recepção passa ao aluno; ela não fica guardada em lugar nenhum e não aparece de novo.
     */
    @PostMapping("/{id}/login")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<LoginsDeAlunos.LoginCriado> criarLogin(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                // Senha na resposta: nenhum cache (navegador, proxy) pode guardar.
                .cacheControl(CacheControl.noStore())
                .body(service.criarLogin(id));
    }

    @PostMapping("/{id}/inativar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    AlunoResponse inativar(@PathVariable UUID id) {
        return AlunoResponse.de(service.inativar(id));
    }

    @PostMapping("/{id}/reativar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    AlunoResponse reativar(@PathVariable UUID id) {
        return AlunoResponse.de(service.reativar(id));
    }

    record AlunoRequest(
            @NotBlank(message = "é obrigatório")
            @Size(max = Aluno.TAMANHO_MAXIMO_NOME, message = "pode ter no máximo {max} caracteres")
            String nome,

            @NotBlank(message = "é obrigatório")
            @CpfValido
            String cpf,

            @Email(message = "e-mail inválido")
            @Size(max = Aluno.TAMANHO_MAXIMO_EMAIL, message = "pode ter no máximo {max} caracteres")
            String email,

            String telefone,

            @Past(message = "deve estar no passado")
            LocalDate dataNascimento,

            @Size(max = 2000, message = "pode ter no máximo {max} caracteres")
            String observacoes) {

        DadosAluno paraDados() {
            return new DadosAluno(nome, cpf, email, telefone, dataNascimento, observacoes);
        }
    }

    record AlunoResponse(
            UUID id,
            String nome,
            String cpf,
            String email,
            String telefone,
            LocalDate dataNascimento,
            String observacoes,
            boolean ativo,
            Instant criadoEm) {

        static AlunoResponse de(Aluno a) {
            return new AlunoResponse(a.getId(), a.getNome(), a.getCpf(), a.getEmail(), a.getTelefone(),
                    a.getDataNascimento(), a.getObservacoes(), a.isAtivo(), a.getCriadoEm());
        }
    }
}
