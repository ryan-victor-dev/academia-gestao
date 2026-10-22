package br.com.academiagestao.matriculas;

import br.com.academiagestao.planos.FormaPagamento;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** A matrícula copia a forma de pagamento do plano com o mesmo nome (R2). */
class FormaDePagamentoTest {

    @Test
    void cadaFormaDoPlanoViraAMesmaNaMatricula() {
        assertThat(FormaDePagamento.doPlano(FormaPagamento.MENSAL)).isEqualTo(FormaDePagamento.MENSAL);
        assertThat(FormaDePagamento.doPlano(FormaPagamento.A_VISTA)).isEqualTo(FormaDePagamento.A_VISTA);
        // Os nomes vão para o banco e para a API: têm de ser os mesmos.
        assertThat(Arrays.stream(FormaDePagamento.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(FormaPagamento.values()).map(Enum::name).toList());
    }
}
