package br.com.academiagestao.folha;

import br.com.academiagestao.compartilhado.Cpf;
import br.com.academiagestao.compartilhado.DadosInvalidosException;

/**
 * Nome e CPF de quem trabalha na academia sem usar o sistema (ADR 0049): limpeza, manutenção, aula coletiva. Quem
 * tem login não passa por aqui: o nome e o CPF dele são os do login. Java puro.
 */
record SemLogin(String nome, String cpf) {

    static final int TAMANHO_MAXIMO_NOME = 150;

    SemLogin {
        if (nome == null || nome.isBlank()) {
            throw new DadosInvalidosException("nome: é obrigatório");
        }
        nome = nome.strip();
        if (nome.length() > TAMANHO_MAXIMO_NOME) {
            throw new DadosInvalidosException("nome: pode ter no máximo " + TAMANHO_MAXIMO_NOME + " caracteres");
        }
        if (nome.indexOf('\n') >= 0 || nome.indexOf('\r') >= 0) {
            throw new DadosInvalidosException("nome: não pode ter quebra de linha");
        }
        if (cpf == null || cpf.isBlank()) {
            throw new DadosInvalidosException("cpf: é obrigatório");
        }
        if (!Cpf.ehValido(cpf)) {
            throw new DadosInvalidosException("cpf: inválido");
        }
        cpf = Cpf.normalizar(cpf);
    }
}
