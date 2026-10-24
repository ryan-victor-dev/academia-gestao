package br.com.academiagestao.pagamentos;

import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.CobrancaEmAberto;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * O que o ADMIN decide do pagamento online (V42, ADR 0034): quais formas o app oferece, até
 * onde o aluno adianta e quantos dias vale o boleto de uma cobrança vencida.
 */
record ConfiguracaoDoPagamentoOnline(boolean pixLigado, boolean boletoLigado, Adiantar adiantar, int diasDoBoleto,
                                     long versao) {

    static final int DIAS_DO_BOLETO_MAXIMO = 30;

    enum Adiantar {
        /** Só as vencidas e as que vencem hoje. */
        NENHUMA,
        /** Mais a próxima a vencer (o valor inicial aceito pelo dono). */
        PROXIMA,
        TODAS
    }

    ConfiguracaoDoPagamentoOnline {
        if (adiantar == null) {
            throw new PagamentoOnlineInvalidoException("até onde adiantar é obrigatório");
        }
        if (diasDoBoleto < 1 || diasDoBoleto > DIAS_DO_BOLETO_MAXIMO) {
            throw new PagamentoOnlineInvalidoException("o boleto de uma cobrança vencida vale de 1 a "
                    + DIAS_DO_BOLETO_MAXIMO + " dias");
        }
    }

    boolean ligada(Forma forma) {
        return forma == Forma.PIX ? pixLigado : boletoLigado;
    }

    /**
     * As que o aluno pode pagar pelo app hoje: as que já venceram ou vencem hoje, sempre; das
     * que ainda vão vencer, depende de {@link #adiantar}.
     *
     * @param emAberto da que vence primeiro para a última
     */
    List<CobrancaEmAberto> pagaveis(List<CobrancaEmAberto> emAberto, LocalDate hoje) {
        List<CobrancaEmAberto> pagaveis = new ArrayList<>();
        int adiantadas = 0;
        for (CobrancaEmAberto cobranca : emAberto) {
            if (!cobranca.vencimento().isAfter(hoje)) {
                pagaveis.add(cobranca);
            } else if (adiantar == Adiantar.TODAS || adiantar == Adiantar.PROXIMA && adiantadas == 0) {
                pagaveis.add(cobranca);
                adiantadas++;
            }
        }
        return pagaveis;
    }
}
