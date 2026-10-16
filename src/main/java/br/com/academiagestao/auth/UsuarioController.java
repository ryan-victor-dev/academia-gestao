package br.com.academiagestao.auth;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/usuarios")
class UsuarioController {

    private final UsuarioRepository repository;

    UsuarioController(UsuarioRepository repository) {
        this.repository = repository;
    }

    /** Lista os logins do sistema. Só ADMIN. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    List<UsuarioResponse> listar() {
        return repository.findAll(Sort.by("nome")).stream()
                .map(UsuarioResponse::de)
                .toList();
    }
}
