package br.com.academiagestao.pagamentos;

/**
 * O simulado não sobe num servidor de verdade (diagnóstico de 27/10, M1): com ele, o aluno marca a
 * própria cobrança como paga sem pagar. Servidor de verdade é o que tem um {@code DOMINIO} no
 * {@code .env}, a mesma regra do {@code scripts/usuario-validacao.sh}.
 */
final class TravaDoSimulado {

    private TravaDoSimulado() {
    }

    static void conferir(String dominio) {
        if (dominio != null && !dominio.isBlank() && !dominio.equals("localhost")) {
            throw new IllegalStateException("Pagamento online simulado recusado: o .env tem DOMINIO=" + dominio
                    + ", então isto parece um servidor de verdade. Use PAGAMENTOS_PROVEDOR=desligado ou a empresa.");
        }
    }
}
