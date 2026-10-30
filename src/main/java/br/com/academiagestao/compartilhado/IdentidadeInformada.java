package br.com.academiagestao.compartilhado;

import br.com.academiagestao.compartilhado.Identidade.IdentidadeDeGenero;
import br.com.academiagestao.compartilhado.Identidade.OrientacaoSexual;

/**
 * O corpo do pedido e da resposta da identidade (ADR 0047), igual para o aluno e para a equipe. Tudo
 * opcional: nulo é "não respondeu". As regras da descrição ficam na {@link Identidade}, e a quebra
 * delas vira 400 com a mensagem.
 */
public record IdentidadeInformada(IdentidadeDeGenero identidadeDeGenero, String identidadeDeGeneroOutra,
                                  OrientacaoSexual orientacaoSexual, String orientacaoSexualOutra) {

    public Identidade paraIdentidade() {
        return new Identidade(identidadeDeGenero, identidadeDeGeneroOutra, orientacaoSexual, orientacaoSexualOutra);
    }

    public static IdentidadeInformada de(Identidade identidade) {
        return new IdentidadeInformada(identidade.identidadeDeGenero(), identidade.identidadeDeGeneroOutra(),
                identidade.orientacaoSexual(), identidade.orientacaoSexualOutra());
    }
}
