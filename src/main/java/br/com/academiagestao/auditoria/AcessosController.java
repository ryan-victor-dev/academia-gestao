package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.Pagina;
import br.com.academiagestao.compartilhado.RegistroDeAcessos.Tipo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Consulta do registro de acessos (V38, ADR 0031), só para o ADMIN (decisão do dono): quem
 * abriu o cadastro de qual aluno, e os logins com a conta e o IP. Do mais novo para o mais
 * antigo. Ex.: GET /api/auditoria/acessos?alunoId=...  ou  ?conta=fulano@email.com&tipo=LOGIN_FALHO
 */
@RestController
@RequestMapping("/api/auditoria/acessos")
class AcessosController {

    private final AcessosService service;

    AcessosController(AcessosService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    Pagina<AcessosService.Acesso> listar(
            @RequestParam(required = false) Tipo tipo,
            @RequestParam(required = false) UUID usuarioId,
            @RequestParam(required = false) UUID alunoId,
            @RequestParam(required = false) String conta,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
        return service.listar(tipo, usuarioId, alunoId, conta, pagina, tamanho);
    }
}
