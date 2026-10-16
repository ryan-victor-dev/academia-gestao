package br.com.academiagestao.alunos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
