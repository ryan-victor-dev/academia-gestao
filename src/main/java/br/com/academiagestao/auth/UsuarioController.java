package br.com.academiagestao.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/** Gestão dos logins da equipe. Tudo aqui é só para ADMIN. */
@RestController
@RequestMapping("/api/usuarios")
@PreAuthorize("hasRole('ADMIN')")
class UsuarioController {

    private final UsuarioRepository repository;
    private final RedefinicaoDeSenha redefinicaoDeSenha;
    private final EquipeService equipeService;

    UsuarioController(UsuarioRepository repository, RedefinicaoDeSenha redefinicaoDeSenha,
                      EquipeService equipeService) {
        this.repository = repository;
        this.redefinicaoDeSenha = redefinicaoDeSenha;
        this.equipeService = equipeService;
    }

    /** Lista os logins do sistema. */
    @GetMapping
    @Transactional(readOnly = true)
    List<UsuarioResponse> listar() {
        return repository.findAll(Sort.by("nome")).stream()
                .map(UsuarioResponse::de)
                .toList();
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

    record UsuarioCriadoResponse(UUID id, String nome, String email, Perfil perfil, String senhaTemporaria) {

        static UsuarioCriadoResponse de(EquipeService.UsuarioCriado criado) {
            Usuario u = criado.usuario();
            return new UsuarioCriadoResponse(u.getId(), u.getNome(), u.getEmail(), u.getPerfil(),
                    criado.senhaTemporaria());
        }
    }
}
