package br.com.academiagestao.compartilhado;

/**
 * Validação e formatação de CPF. Java puro, sem framework (ADR 0004).
 */
public final class Cpf {

    private Cpf() {
    }

    /** Tira pontos, traço e espaços: "529.982.247-25" vira "52998224725". */
    public static String normalizar(String valor) {
        return valor == null ? "" : valor.replaceAll("[.\\-\\s]", "");
    }

    public static boolean ehValido(String valor) {
        String cpf = normalizar(valor);
        if (!cpf.matches("\\d{11}")) {
            return false;
        }
        // 000.000.000-00, 111.111.111-11... passam na conta dos dígitos, mas não existem.
        if (cpf.chars().distinct().count() == 1) {
            return false;
        }
        return cpf.charAt(9) == digitoVerificador(cpf, 9)
                && cpf.charAt(10) == digitoVerificador(cpf, 10);
    }

    /**
     * Calcula o dígito na posição {@code tamanho} a partir dos dígitos anteriores:
     * cada um é multiplicado por um peso decrescente (10, 9, 8... ou 11, 10, 9...),
     * e o dígito é o resto de (soma × 10) ÷ 11, com 10 virando 0.
     */
    private static char digitoVerificador(String cpf, int tamanho) {
        int soma = 0;
        for (int i = 0; i < tamanho; i++) {
            soma += (cpf.charAt(i) - '0') * (tamanho + 1 - i);
        }
        int resto = (soma * 10) % 11;
        return (char) ('0' + (resto == 10 ? 0 : resto));
    }

    /** "52998224725" vira "529.982.247-25". */
    public static String formatar(String cpf) {
        String n = normalizar(cpf);
        return n.substring(0, 3) + "." + n.substring(3, 6) + "." + n.substring(6, 9) + "-" + n.substring(9);
    }
}
