package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.Pagina;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/** Gestão dos logins da equipe. Tudo aqui é só para ADMIN. */
@RestController
@RequestMapping("/api/usuarios")
@PreAuthorize("hasRole('ADMIN')")
class UsuarioController {

    private final RedefinicaoDeSenha redefinicaoDeSenha;
    private final EquipeService equipeService;

    UsuarioController(RedefinicaoDeSenha redefinicaoDeSenha, EquipeService equipeService) {
        this.redefinicaoDeSenha = redefinicaoDeSenha;
        this.equipeService = equipeService;
    }

    /** Os logins da equipe (sem os de aluno, que vêm paginados de /alunos). */
    @GetMapping
    List<UsuarioResponse> listar() {
        return equipeService.listar();
    }

    /** Os logins de aluno, paginados, com busca por qualquer parte do nome ou do e-mail (diagnóstico de 27/10, B4). */
    @GetMapping("/alunos")
    Pagina<UsuarioResponse> listarLoginsDeAlunos(
            @RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
        return Pagina.de(equipeService.listarLoginsDeAlunos(busca, pagina, tamanho), UsuarioResponse::de);
    }

    /**
     * Cria um login da equipe (ADMIN, RECEPCAO ou INSTRUTOR) com senha temporária, que
     * aparece só nesta resposta e precisa ser trocada no primeiro acesso.
     */
    @PostMapping
    ResponseEntity<UsuarioCriadoResponse> criar(@Valid @RequestBody NovoUsuarioRequest request) {
        EquipeService.UsuarioCriado criado = equipeService.criar(request.nome(), request.email(), request.perfil());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(criado.usuario().getId()).toUri();
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(UsuarioCriadoResponse.de(criado));
    }

    /** Funcionário que saiu: o login para de funcionar e as sessões abertas caem na hora. */
    @PostMapping("/{id}/desativar")
    UsuarioResponse desativar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return equipeService.desativar(id, UUID.fromString(jwt.getSubject()));
    }

    /** Login criado por engano e nunca usado (ADR 0043); usado, 409: desative. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> excluir(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        equipeService.excluir(id, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Muda nome e perfil. Mudar o perfil derruba as sessões da pessoa na hora (o perfil vai
     * no token); mudar só o nome, não.
     */
    @PutMapping("/{id}")
    UsuarioResponse editar(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                           @Valid @RequestBody EdicaoDeUsuarioRequest request) {
        return equipeService.editar(id, request.nome(), request.perfil(), UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/{id}/reativar")
    UsuarioResponse reativar(@PathVariable UUID id) {
        return equipeService.reativar(id);
    }

    /**
     * "Esqueci minha senha" da equipe: nova senha temporária para um usuário que não seja
     * ADMIN. A senha aparece só nesta resposta.
     */
    @PostMapping("/{id}/nova-senha")
    ResponseEntity<CredenciaisTemporarias> novaSenha(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(redefinicaoDeSenha.peloAdmin(id));
    }

    record NovoUsuarioRequest(
            @NotBlank(message = "é obrigatório") @Size(max = 150, message = "pode ter no máximo {max} caracteres")
            String nome,
            @NotBlank(message = "é obrigatório") @Email(message = "e-mail inválido")
            @Size(max = 150, message = "pode ter no máximo {max} caracteres")
            String email,
            @NotNull(message = "é obrigatório")
            Perfil perfil) {
    }

    record EdicaoDeUsuarioRequest(
            @NotBlank(message = "é obrigatório") @Size(max = 150, message = "pode ter no máximo {max} caracteres")
            String nome,
            @NotNull(message = "é obrigatório")
            Perfil perfil) {
    }

    record UsuarioCriadoResponse(UUID id, String nome, String email, Perfil perfil, String senhaTemporaria) {

        static UsuarioCriadoResponse de(EquipeService.UsuarioCriado criado) {
            Usuario u = criado.usuario();
            return new UsuarioCriadoResponse(u.getId(), u.getNome(), u.getEmail(), u.getPerfil(),
                    criado.senhaTemporaria());
        }
    }
}
