package br.com.academiagestao.compartilhado;

/**
 * Texto com caractere de controle (diagnóstico da etapa 7, T4). O PostgreSQL recusa o byte
 * nulo num texto, e o erro chegava ao cliente como 500, em qualquer módulo. Os outros
 * (U+0001 a U+001F) o banco aceita, mas são lixo num nome ou numa observação. Só a quebra
 * de linha, o retorno de carro e o tab são aceitos. Conferido na entrada, para a API
 * inteira: no JSON ({@link TextoSemCaractereDeControle}) e nos parâmetros e cabeçalhos
 * ({@link TextoNaUrlSemCaractereDeControle}).
 */
class CaractereDeControleException extends DadosInvalidosException {

    CaractereDeControleException(String campo) {
        super(campo + ": tem um caractere de controle (só quebra de linha e tab são aceitos)");
    }

    static void conferir(String campo, String valor) {
        if (valor == null) {
            return;
        }
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            if (c < ' ' && c != '\n' && c != '\r' && c != '\t') {
                throw new CaractereDeControleException(campo);
            }
        }
    }
}
