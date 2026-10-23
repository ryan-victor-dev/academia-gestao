package br.com.academiagestao.alunos;

import br.com.academiagestao.auth.ClaimsDoToken;
import br.com.academiagestao.auth.CredenciaisTemporarias;
import br.com.academiagestao.compartilhado.Pagina;
import br.com.academiagestao.compartilhado.RegistroDeAcessos;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/alunos")
class AlunoController {

    private final AlunoService service;
    private final RegistroDeAcessos acessos;

    AlunoController(AlunoService service, RegistroDeAcessos acessos) {
        this.service = service;
        this.acessos = acessos;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<AlunoResponse> cadastrar(@Valid @RequestBody AlunoRequest request) {
        Aluno aluno = service.cadastrar(request.paraDados());
        // 201 Created com o endereço do novo recurso no cabeçalho Location.
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(aluno.getId()).toUri();
        return ResponseEntity.created(location).eTag(VersaoEsperada.etag(aluno.getVersao()))
                .body(AlunoResponse.de(aluno));
    }

    /**
     * Lista paginada, em ordem de nome. Por padrão só alunos ativos.
     * Ex.: GET /api/alunos?busca=ana&pagina=0&tamanho=20
     * Só o resumo, para todos os perfis: os dados pessoais (CPF, e-mail, telefone, observações)
     * saem só pelo cadastro aberto, que vai para o registro de acessos. Com eles na lista, quem
     * quisesse ler cadastros sem deixar rastro usaria a busca (diagnóstico da etapa 8, M1).
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR')")
    Pagina<AlunoNaLista> listar(
            Authentication autenticacao,
            @RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "false") boolean incluirInativos,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "deve ser 0 ou maior") int pagina,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "deve ser pelo menos {value}")
            @Max(value = 100, message = "pode ser no máximo {value}") int tamanho) {
        // Sem busca por CPF para o instrutor: pelo resultado, ele descobriria de quem é cada CPF.
        return Pagina.de(service.listar(busca, incluirInativos, !ehInstrutor(autenticacao), pagina, tamanho),
                AlunoNaLista::de);
    }

    /**
     * O aluno vê o próprio cadastro, mesmo inativo (é direito dele, LGPD art. 18).
     * O id vem do token, nunca da URL: não há como pedir o cadastro de outra pessoa.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('ALUNO')")
    AlunoResponse meuCadastro(@AuthenticationPrincipal Jwt jwt) {
        String alunoId = jwt.getClaimAsString(ClaimsDoToken.ALUNO_ID);
        if (alunoId == null) {
            throw new AccessDeniedException("Token de aluno sem alunoId");
        }
        return AlunoResponse.de(service.buscar(UUID.fromString(alunoId)));
    }

    // O instrutor também consulta: precisa ver o aluno para montar a ficha de treino.
    // Quem abriu qual cadastro vai para o registro de acessos (ADR 0031), qualquer perfil da
    // equipe: o instrutor também vê as observações, que podem ter dados de saúde. Gravado antes
    // de responder: se o registro falhar, o cadastro não sai.
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO', 'INSTRUTOR')")
    ResponseEntity<? extends VisaoDoAluno> buscar(Authentication autenticacao, @PathVariable UUID id,
                                                  HttpServletRequest requisicao) {
        Aluno aluno = service.buscar(id);
        acessos.consultaDeAluno(UUID.fromString(autenticacao.getName()), aluno.getId(), requisicao.getRemoteAddr());
        if (ehInstrutor(autenticacao)) {
            return ResponseEntity.ok(AlunoParaInstrutor.de(aluno));
        }
        return comEtag(aluno);
    }

    private static boolean ehInstrutor(Authentication autenticacao) {
        return autenticacao.getAuthorities().stream()
                .anyMatch(a -> "ROLE_INSTRUTOR".equals(a.getAuthority()));
    }

    /**
     * Substitui todos os dados editáveis: campo opcional que não vier fica vazio.
     * Exige If-Match com o ETag do GET: sem ele, 428; se o aluno mudou desde então, 412.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<AlunoResponse> atualizar(@PathVariable UUID id,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            @Valid @RequestBody AlunoRequest request) {
        return comEtag(service.atualizar(id, request.paraDados(), ifMatch));
    }

    private static ResponseEntity<AlunoResponse> comEtag(Aluno aluno) {
        return ResponseEntity.ok().eTag(VersaoEsperada.etag(aluno.getVersao())).body(AlunoResponse.de(aluno));
    }

    /**
     * Cria o acesso do aluno ao sistema. A resposta traz a senha temporária, que a
     * recepção passa ao aluno; ela não fica guardada em lugar nenhum e não aparece de novo.
     */
    @PostMapping("/{id}/login")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<CredenciaisTemporarias> criarLogin(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                // Senha na resposta: nenhum cache (navegador, proxy) pode guardar.
                .cacheControl(CacheControl.noStore())
                .body(service.criarLogin(id));
    }

    /** O aluno esqueceu a senha: nova senha temporária, que só aparece nesta resposta. */
    @PostMapping("/{id}/login/nova-senha")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<CredenciaisTemporarias> novaSenha(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.redefinirSenha(id));
    }

    @PostMapping("/{id}/inativar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<AlunoResponse> inativar(@PathVariable UUID id) {
        return comEtag(service.inativar(id));
    }

    @PostMapping("/{id}/reativar")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPCAO')")
    ResponseEntity<AlunoResponse> reativar(@PathVariable UUID id) {
        return comEtag(service.reativar(id));
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

    /** Um aluno na lista ou na busca: o suficiente para achar e abrir o cadastro. */
    record AlunoNaLista(UUID id, String nome, LocalDate dataNascimento, boolean ativo) {

        static AlunoNaLista de(Aluno a) {
            return new AlunoNaLista(a.getId(), a.getNome(), a.getDataNascimento(), a.isAtivo());
        }
    }

    /** O que a API mostra de um aluno; o formato depende de quem pergunta. */
    sealed interface VisaoDoAluno permits AlunoResponse, AlunoParaInstrutor {
    }

    /**
     * Visão do instrutor: só o necessário para montar o treino (LGPD, minimização).
     * Sem CPF, e-mail e telefone. A data de nascimento fica porque a idade importa no treino.
     */
    record AlunoParaInstrutor(UUID id, String nome, LocalDate dataNascimento, String observacoes,
                              boolean ativo) implements VisaoDoAluno {

        static AlunoParaInstrutor de(Aluno a) {
            return new AlunoParaInstrutor(a.getId(), a.getNome(), a.getDataNascimento(), a.getObservacoes(),
                    a.isAtivo());
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
            Instant criadoEm) implements VisaoDoAluno {

        static AlunoResponse de(Aluno a) {
            return new AlunoResponse(a.getId(), a.getNome(), a.getCpf(), a.getEmail(), a.getTelefone(),
                    a.getDataNascimento(), a.getObservacoes(), a.isAtivo(), a.getCriadoEm());
        }
    }
}
