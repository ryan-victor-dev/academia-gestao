package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.Cpf;
import java.util.concurrent.ThreadLocalRandom;

/** Gera CPFs válidos (com dígitos verificadores certos) para os testes não colidirem. */
public final class CpfsParaTeste {

    private CpfsParaTeste() {
    }

    public static String aleatorio() {
        StringBuilder cpf = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            cpf.append(ThreadLocalRandom.current().nextInt(10));
        }
        cpf.append(digito(cpf));
        cpf.append(digito(cpf));
        String resultado = cpf.toString();
        // Sequências repetidas (ex.: 000000000-00) são recusadas pela regra; sorteia de novo.
        return Cpf.ehValido(resultado) ? resultado : aleatorio();
    }

    private static int digito(CharSequence base) {
        int soma = 0;
        for (int i = 0; i < base.length(); i++) {
            soma += (base.charAt(i) - '0') * (base.length() + 1 - i);
        }
        int resto = (soma * 10) % 11;
        return resto == 10 ? 0 : resto;
    }
}
