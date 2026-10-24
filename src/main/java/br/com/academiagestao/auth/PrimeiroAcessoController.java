package br.com.academiagestao.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Primeiro acesso do aluno (ADR 0039): rotas sem login, com os limites do login. */
@RestController
@RequestMapping("/api/auth/primeiro-acesso")
class PrimeiroAcessoController {

    private final PrimeiroAcesso primeiroAcesso;

    PrimeiroAcessoController(PrimeiroAcesso primeiroAcesso) {
        this.primeiroAcesso = primeiroAcesso;
    }

    /** Sempre 202: se o CPF e o e-mail forem de um aluno ativo sem login, o código chega no e-mail. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    void pedir(@Valid @RequestBody PedidoRequest request, HttpServletRequest requisicao) {
        primeiroAcesso.pedir(request.cpf(), request.email(), requisicao.getRemoteAddr());
    }

    @PostMapping("/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void criarSenha(@Valid @RequestBody SenhaRequest request, HttpServletRequest requisicao) {
        primeiroAcesso.criarSenha(request.cpf(), request.email(), request.codigo(), request.senha(),
                requisicao.getRemoteAddr());
    }

    // O CPF com ou sem pontuação (no máximo 14: "000.000.000-00"); o e-mail, como no "esqueci a senha".
    record PedidoRequest(@NotBlank(message = "é obrigatório") @Size(max = 14) String cpf,
                         @NotBlank(message = "é obrigatório") @Email(message = "não é um e-mail válido")
                         @Size(max = 150) String email) {
    }

    record SenhaRequest(@NotBlank(message = "é obrigatório") @Size(max = 14) String cpf,
                        @NotBlank(message = "é obrigatório") @Email(message = "não é um e-mail válido")
                        @Size(max = 150) String email,
                        @NotBlank(message = "é obrigatório") String codigo,
                        @NotBlank(message = "é obrigatório") String senha) {
    }
}
