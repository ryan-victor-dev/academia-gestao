package br.com.academiagestao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dados do primeiro ADMIN, lidos de variáveis de ambiente (ver .env.example).
 * Só são usados enquanto o banco não tem nenhum ADMIN.
 */
@ConfigurationProperties(prefix = "academia.admin-inicial")
record AdminInicialProperties(String nome, String email, String senha) {

    boolean configurado() {
        return email != null && !email.isBlank() && senha != null && !senha.isBlank();
    }
}
