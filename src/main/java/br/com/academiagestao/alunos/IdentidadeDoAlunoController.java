package br.com.academiagestao.alunos;

import br.com.academiagestao.auth.ClaimsDoToken;
import br.com.academiagestao.compartilhado.IdentidadeInformada;
import br.com.academiagestao.compartilhado.RegistroDeAcessos;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Identidade de gênero e orientação sexual do aluno (ADR 0047, decisão 2): dado sensível, só o ADMIN e o
 * próprio aluno veem. A recepção e o instrutor não chegam aqui, e o cadastro do aluno ({@code GET
 * /api/alunos/{id}}) não traz esses campos. As respostas não ficam em cache.
 */
@RestController
@RequestMapping("/api/alunos")
class IdentidadeDoAlunoController {

    private final IdentidadeDoAlunoService service;
    private final RegistroDeAcessos acessos;

    IdentidadeDoAlunoController(IdentidadeDoAlunoService service, RegistroDeAcessos acessos) {
        this.service = service;
        this.acessos = acessos;
    }

    /** A do próprio aluno: o id vem do token, nunca da URL. */
    @GetMapping("/me/identidade")
    @PreAuthorize("hasRole('ALUNO')")
    ResponseEntity<IdentidadeInformada> minha(@AuthenticationPrincipal Jwt jwt) {
        return semCache(IdentidadeInformada.de(service.buscar(alunoDoToken(jwt))));
    }

    /** O aluno inativo (só de leitura, ADR 0029) não altera: pede ao ADMIN. */
    @PutMapping("/me/identidade")
    @PreAuthorize("hasRole('ALUNO')")
    ResponseEntity<IdentidadeInformada> informarAMinha(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestBody IdentidadeInformada pedido) {
        return semCache(IdentidadeInformada.de(service.gravar(alunoDoToken(jwt), pedido.paraIdentidade())));
    }

    /** O ADMIN abrindo o dado sensível de um aluno conta como consulta ao cadastro (ADR 0031). */
    @GetMapping("/{id}/identidade")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<IdentidadeInformada> doAluno(Authentication autenticacao, @PathVariable UUID id,
                                               HttpServletRequest requisicao) {
        IdentidadeInformada identidade = IdentidadeInformada.de(service.buscar(id));
        acessos.consultaDeAluno(UUID.fromString(autenticacao.getName()), id, requisicao.getRemoteAddr());
        return semCache(identidade);
    }

    /** O ADMIN registra o que o aluno informou no balcão (ele pode não ter login). */
    @PutMapping("/{id}/identidade")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<IdentidadeInformada> informarDoAluno(@PathVariable UUID id, @RequestBody IdentidadeInformada pedido) {
        return semCache(IdentidadeInformada.de(service.gravar(id, pedido.paraIdentidade())));
    }

    private static UUID alunoDoToken(Jwt jwt) {
        String alunoId = jwt.getClaimAsString(ClaimsDoToken.ALUNO_ID);
        if (alunoId == null) {
            throw new AccessDeniedException("Token de aluno sem alunoId");
        }
        return UUID.fromString(alunoId);
    }

    private static ResponseEntity<IdentidadeInformada> semCache(IdentidadeInformada corpo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(corpo);
    }
}
