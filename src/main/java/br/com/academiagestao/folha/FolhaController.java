package br.com.academiagestao.folha;

import br.com.academiagestao.compartilhado.DadosInvalidosException;
import br.com.academiagestao.compartilhado.RegistroDeAcessos;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Quem recebe pela folha (ADR 0049): a equipe com login, que aparece sozinha, e quem trabalha sem usar o sistema.
 * Só o ADMIN: o valor combinado é o salário da pessoa. Por isso, cada consulta vai para o registro de acessos (uma linha
 * por consulta), e a resposta não fica em cache.
 */
@RestController
@RequestMapping("/api/folha/pessoas")
class FolhaController {

    private final FolhaService service;
    private final RegistroDeAcessos acessos;

    FolhaController(FolhaService service, RegistroDeAcessos acessos) {
        this.service = service;
        this.acessos = acessos;
    }

    /** Pelo nome. Quem tem login e ainda não está na folha vem sem {@code id} e sem vínculo, para o ADMIN definir. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<List<PessoaResponse>> listar(Authentication autenticacao, HttpServletRequest requisicao,
                                                @RequestParam(defaultValue = "false") boolean incluirInativos) {
        List<PessoaResponse> pessoas = service.listar(incluirInativos).stream().map(PessoaResponse::de).toList();
        acessos.consultaDaFolha(UUID.fromString(autenticacao.getName()), requisicao.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pessoas);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PessoaResponse> buscar(Authentication autenticacao, HttpServletRequest requisicao, @PathVariable UUID id) {
        PessoaDaFolha pessoa = service.buscar(id);
        acessos.consultaDaFolha(UUID.fromString(autenticacao.getName()), requisicao.getRemoteAddr());
        return comEtag(pessoa);
    }

    /** Com {@code usuarioId}: alguém da equipe com login (sem nome nem CPF). Sem: quem não tem login (nome e CPF). */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PessoaResponse> incluir(@RequestBody PessoaRequest pedido) {
        Combinado combinado = new Combinado(pedido.vinculo(), pedido.valorCombinado());
        PessoaDaFolha pessoa;
        if (pedido.usuarioId() != null) {
            if (pedido.nome() != null || pedido.cpf() != null) {
                throw new DadosInvalidosException("nome: quem tem login usa o nome e o CPF do login");
            }
            pessoa = service.incluirLogin(pedido.usuarioId(), combinado);
        } else {
            pessoa = service.cadastrarSemLogin(new SemLogin(pedido.nome(), pedido.cpf()), combinado);
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(pessoa.id()).toUri();
        return ResponseEntity.created(location).eTag(VersaoEsperada.etag(pessoa.versao())).body(PessoaResponse.de(pessoa));
    }

    /**
     * Substitui o combinado (e, de quem não tem login, o nome, o CPF e a situação). Exige If-Match com o ETag do GET.
     * De quem tem login, mande só o vínculo e o valor.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PessoaResponse> editar(@PathVariable UUID id,
                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                          @RequestBody EdicaoRequest pedido) {
        Combinado combinado = new Combinado(pedido.vinculo(), pedido.valorCombinado());
        return comEtag(service.editar(id, ifMatch, pedido.nome(), pedido.cpf(), combinado, pedido.ativo()));
    }

    private static ResponseEntity<PessoaResponse> comEtag(PessoaDaFolha pessoa) {
        return ResponseEntity.ok().eTag(VersaoEsperada.etag(pessoa.versao())).cacheControl(CacheControl.noStore())
                .body(PessoaResponse.de(pessoa));
    }

    record PessoaRequest(UUID usuarioId, String nome, String cpf, Vinculo vinculo, BigDecimal valorCombinado) {
    }

    record EdicaoRequest(String nome, String cpf, Vinculo vinculo, BigDecimal valorCombinado, Boolean ativo) {
    }

    /** {@code perfil} nulo: sem login. {@code vinculo} nulo: alguém da equipe com login que ainda não está na folha. */
    record PessoaResponse(UUID id, UUID usuarioId, String nome, String cpf, String perfil, Vinculo vinculo,
                          BigDecimal valorCombinado, boolean ativo, long versao) {

        static PessoaResponse de(PessoaDaFolha p) {
            return new PessoaResponse(p.id(), p.usuarioId(), p.nome(), p.cpf(), p.perfil(),
                    p.combinado() == null ? null : p.combinado().vinculo(),
                    p.combinado() == null ? null : p.combinado().valor(), p.ativo(), p.versao());
        }
    }
}
