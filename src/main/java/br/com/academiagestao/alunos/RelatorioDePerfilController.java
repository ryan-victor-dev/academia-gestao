package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.RegistroDeAcessos;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * O relatório de idades e gênero (ADR 0048, decisão 4): só o ADMIN. Traz a orientação sexual de todos, dado sensível
 * (LGPD): sem cache, e cada abertura vai para o registro de acessos (uma linha por abertura).
 */
@RestController
class RelatorioDePerfilController {

    private final RelatorioDePerfilService service;
    private final RegistroDeAcessos acessos;

    RelatorioDePerfilController(RelatorioDePerfilService service, RegistroDeAcessos acessos) {
        this.service = service;
        this.acessos = acessos;
    }

    /** {@code inativos}: inclui quem saiu da academia (alunos inativos e equipe desativada). */
    @GetMapping("/api/relatorios/idades-e-genero")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<RelatorioDePerfilService.Relatorio> relatorio(Authentication autenticacao, HttpServletRequest requisicao,
                                                                 @RequestParam(defaultValue = "false") boolean inativos) {
        RelatorioDePerfilService.Relatorio relatorio = service.gerar(inativos);
        acessos.relatorioDePerfil(UUID.fromString(autenticacao.getName()), requisicao.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(relatorio);
    }
}
