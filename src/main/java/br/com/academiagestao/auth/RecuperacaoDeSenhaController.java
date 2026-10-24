package br.com.academiagestao.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Esqueci a senha" (ADR 0038): rotas sem login, com os limites do login. */
@RestController
@RequestMapping("/api/auth")
class RecuperacaoDeSenhaController {

    private final RecuperacaoDeSenha recuperacao;

    RecuperacaoDeSenhaController(RecuperacaoDeSenha recuperacao) {
        this.recuperacao = recuperacao;
    }

    /** O que a tela de login oferece. */
    @GetMapping("/opcoes")
    OpcoesResponse opcoes() {
        return new OpcoesResponse(recuperacao.disponivel());
    }

    /** Sempre 202: se o e-mail for de quem pode recuperar, o código chega nele. */
    @PostMapping("/esqueci-senha")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void pedir(@Valid @RequestBody PedidoRequest request, HttpServletRequest requisicao) {
        recuperacao.pedir(request.email(), requisicao.getRemoteAddr());
    }

    @PostMapping("/redefinir-senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void redefinir(@Valid @RequestBody RedefinicaoRequest request, HttpServletRequest requisicao) {
        recuperacao.redefinir(request.email(), request.codigo(), request.novaSenha(), requisicao.getRemoteAddr());
    }

    record OpcoesResponse(boolean esqueciSenha) {
    }

    // Mesmo tamanho máximo do e-mail cadastrado (é o que vai para a tabela do limite).
    record PedidoRequest(@NotBlank(message = "é obrigatório") @Email(message = "não é um e-mail válido")
                         @Size(max = 150) String email) {
    }

    record RedefinicaoRequest(@NotBlank(message = "é obrigatório") @Email(message = "não é um e-mail válido")
                              @Size(max = 150) String email,
                              @NotBlank(message = "é obrigatório") String codigo,
                              @NotBlank(message = "é obrigatório") String novaSenha) {
    }
}
