package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.Auditoria.Entidade;
import br.com.academiagestao.compartilhado.Pagina;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Consulta da trilha, só para o ADMIN: tem dados pessoais (valores anteriores de cadastros)
 * e mostra o que cada funcionário fez. Do mais novo para o mais antigo.
 * Ex.: GET /api/auditoria?entidade=PLANO&entidadeId=...&pagina=0&tamanho=20
 */
@RestController
@RequestMapping("/api/auditoria")
class AuditoriaController {

    private final AuditoriaService service;

    AuditoriaController(AuditoriaService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    Pagina<AuditoriaService.Registro> listar(
            @RequestParam(required = false) Entidade entidade,
            @RequestParam(required = false) UUID entidadeId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
        return service.listar(entidade, entidadeId, pagina, tamanho);
    }
}
