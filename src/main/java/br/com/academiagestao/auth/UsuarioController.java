package br.com.academiagestao.auth;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/usuarios")
class UsuarioController {

    private final UsuarioRepository repository;
    private final RedefinicaoDeSenha redefinicaoDeSenha;

    UsuarioController(UsuarioRepository repository, RedefinicaoDeSenha redefinicaoDeSenha) {
        this.repository = repository;
        this.redefinicaoDeSenha = redefinicaoDeSenha;
    }

    /** Lista os logins do sistema. Só ADMIN. */
    /**
     * "Esqueci minha senha" da equipe: o ADMIN gera uma nova senha temporária para um
     * usuário que não seja ADMIN. A senha aparece só nesta resposta.
     */
    @PostMapping("/{id}/nova-senha")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<CredenciaisTemporarias> novaSenha(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(redefinicaoDeSenha.peloAdmin(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    List<UsuarioResponse> listar() {
        return repository.findAll(Sort.by("nome")).stream()
                .map(UsuarioResponse::de)
                .toList();
    }
}
