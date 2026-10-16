package br.com.academiagestao.auth;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Regras de tamanho de senha, iguais para o primeiro ADMIN e para a troca de senha. */
final class PoliticaDeSenha {

    static final int TAMANHO_MINIMO = 12;
    // O BCrypt ignora tudo depois do 72º byte; o Spring Security recusa senhas maiores.
    static final int TAMANHO_MAXIMO_BYTES = 72;

    private PoliticaDeSenha() {
    }

    /** O motivo pelo qual a senha não é aceita, ou vazio se ela é aceita. */
    static Optional<String> problemaCom(String senha) {
        if (senha == null || senha.length() < TAMANHO_MINIMO) {
            return Optional.of("a senha precisa ter pelo menos " + TAMANHO_MINIMO + " caracteres");
        }
        if (senha.getBytes(StandardCharsets.UTF_8).length > TAMANHO_MAXIMO_BYTES) {
            return Optional.of("a senha pode ter no máximo " + TAMANHO_MAXIMO_BYTES + " bytes");
        }
        return Optional.empty();
    }
}
