package br.com.academiagestao.matriculas;

import br.com.academiagestao.cobranca.dominio.MatriculaParaCobranca;

import java.util.List;

/**
 * Tradução da matrícula para a cobrança (ADR 0024). Fica fora da entidade (R2): a
 * {@link Matricula} guarda as regras do próprio módulo, e só aqui se fala a língua da
 * cobrança.
 */
final class MatriculaNaCobranca {

    private MatriculaNaCobranca() {
    }

    /**
     * O que a cobrança precisa saber da matrícula: valor, duração, forma de pagamento, início,
     * os dias realmente parados de cada trancamento e o encerramento.
     */
    static MatriculaParaCobranca de(Matricula m) {
        // Um trancamento desfeito tem 0 dias e não empurra nada.
        List<MatriculaParaCobranca.Pausa> pausas = m.getTrancamentos().stream()
                .map(t -> new MatriculaParaCobranca.Pausa(t.getInicio(), t.dias()))
                .toList();
        MatriculaParaCobranca.Encerramento encerramento = null;
        if (m.getCanceladaEm() != null) {
            encerramento = new MatriculaParaCobranca.Encerramento(
                    MatriculaParaCobranca.Encerramento.Tipo.CANCELAMENTO, Matricula.hoje(m.getCanceladaEm()), m.getMulta());
        } else if (m.getTrocadaEm() != null) {
            encerramento = new MatriculaParaCobranca.Encerramento(
                    MatriculaParaCobranca.Encerramento.Tipo.TROCA, m.getTrocadaEm(), m.getMulta());
        }
        return new MatriculaParaCobranca(m.getId(), m.getAlunoId(), m.getValorMensal(), m.getDuracaoMeses(),
                m.getFormaPagamento() == FormaDePagamento.A_VISTA, m.getInicio(), pausas, encerramento,
                m.getPrimeiroVencimento(), m.getCredito());
    }

    /** A matrícula nova de uma troca continua a anterior (ADR 0026), com o que a cobrança calculou. */
    static void continuar(Matricula nova, MatriculaParaCobranca.Continuacao continuacao) {
        nova.continuar(continuacao.primeiroVencimento(), continuacao.credito());
    }
}
