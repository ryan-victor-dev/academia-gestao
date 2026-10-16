package br.com.academiagestao.auth;

import java.security.SecureRandom;

/**
 * Senha temporária para ditar ou anotar: 16 caracteres em 4 grupos (ex.: "Kp7m-Qx3a-..."),
 * sem caracteres que se confundem (0/O, 1/l/I). São ~93 bits de aleatoriedade.
 */
final class GeradorDeSenhaTemporaria {

    static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private GeradorDeSenhaTemporaria() {
    }

    static String gerar() {
        StringBuilder senha = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                senha.append('-');
            }
            senha.append(ALFABETO.charAt(ALEATORIO.nextInt(ALFABETO.length())));
        }
        return senha.toString();
    }
}
