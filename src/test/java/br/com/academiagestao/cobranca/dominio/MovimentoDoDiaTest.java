package br.com.academiagestao.cobranca.dominio;

import br.com.academiagestao.cobranca.dominio.MovimentoDoDia.Movimento;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static br.com.academiagestao.cobranca.dominio.Lancamento.Tipo.ESTORNO;
import static br.com.academiagestao.cobranca.dominio.Lancamento.Tipo.PAGAMENTO;
import static br.com.academiagestao.cobranca.dominio.MeioDePagamento.CARTAO_CREDITO;
import static br.com.academiagestao.cobranca.dominio.MeioDePagamento.CARTAO_DEBITO;
import static br.com.academiagestao.cobranca.dominio.MeioDePagamento.DINHEIRO;
import static br.com.academiagestao.cobranca.dominio.MeioDePagamento.BOLETO;
import static br.com.academiagestao.cobranca.dominio.MeioDePagamento.PIX;
import static br.com.academiagestao.cobranca.dominio.MeioDePagamento.PIX_ONLINE;
import static org.assertj.core.api.Assertions.assertThat;

/** O movimento do dia por forma (ADR 0031): pagamentos menos estornos. */
class MovimentoDoDiaTest {

    private static BigDecimal reais(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void todaFormaApareceNaOrdemComZero() {
        assertThat(MovimentoDoDia.VAZIO.porForma().keySet()).containsExactly(DINHEIRO, PIX, CARTAO_DEBITO,
                CARTAO_CREDITO, PIX_ONLINE, BOLETO);
        assertThat(MovimentoDoDia.VAZIO.porForma().values()).containsOnly(Movimento.ZERO);
        assertThat(Movimento.ZERO.liquido()).isEqualByComparingTo("0");
    }

    @Test
    void pagamentosEEstornosSomamNaForma() {
        MovimentoDoDia dia = MovimentoDoDia.VAZIO
                .com(DINHEIRO, PAGAMENTO, reais("100.00"), 1)
                .com(DINHEIRO, PAGAMENTO, reais("50.50"), 2)
                .com(DINHEIRO, ESTORNO, reais("30.00"), 1)
                .com(PIX, ESTORNO, reais("10.00"), 1);

        Movimento dinheiro = dia.porForma().get(DINHEIRO);
        assertThat(dinheiro.recebido()).isEqualByComparingTo("150.50");
        assertThat(dinheiro.pagamentos()).isEqualTo(3);
        assertThat(dinheiro.estornado()).isEqualByComparingTo("30.00");
        assertThat(dinheiro.estornos()).isEqualTo(1);
        assertThat(dinheiro.liquido()).isEqualByComparingTo("120.50");
        // Estorno de um pagamento de outro dia: o líquido do dia pode ficar negativo.
        assertThat(dia.porForma().get(PIX).liquido()).isEqualByComparingTo("-10.00");
        assertThat(dia.porForma().get(CARTAO_DEBITO)).isEqualTo(Movimento.ZERO);
    }

    @Test
    void doisDiasSomados() {
        MovimentoDoDia um = MovimentoDoDia.VAZIO.com(PIX, PAGAMENTO, reais("100"), 1)
                .com(DINHEIRO, ESTORNO, reais("5"), 1);
        MovimentoDoDia outro = MovimentoDoDia.VAZIO.com(PIX, PAGAMENTO, reais("20"), 2)
                .com(CARTAO_CREDITO, PAGAMENTO, reais("7"), 1);

        MovimentoDoDia soma = um.mais(outro);

        assertThat(soma.porForma().get(PIX)).isEqualTo(new Movimento(reais("120.00"), 3, reais("0.00"), 0));
        assertThat(soma.porForma().get(DINHEIRO)).isEqualTo(new Movimento(reais("0.00"), 0, reais("5.00"), 1));
        assertThat(soma.porForma().get(CARTAO_CREDITO)).isEqualTo(new Movimento(reais("7.00"), 1, reais("0.00"), 0));
        assertThat(new Movimento(reais("1"), 1, reais("2"), 3).mais(new Movimento(reais("10"), 10, reais("20"), 30)))
                .isEqualTo(new Movimento(reais("11"), 11, reais("22"), 33));
    }

    @Test
    void semDinheiroTiraSoADaGaveta() {
        MovimentoDoDia dia = MovimentoDoDia.VAZIO.com(DINHEIRO, PAGAMENTO, reais("100"), 1)
                .com(PIX, PAGAMENTO, reais("40"), 1);

        assertThat(dia.semDinheiro().keySet()).containsExactly(PIX, CARTAO_DEBITO, CARTAO_CREDITO);
        assertThat(dia.semDinheiro().get(PIX).recebido()).isEqualByComparingTo("40");
        assertThat(dia.porForma()).containsKey(DINHEIRO);
    }

    /** O online (ADR 0034) não passa pelo caixa: some da visão do caixa, mas fica no total do dia. */
    @Test
    void doBalcaoTiraOOnline() {
        MovimentoDoDia dia = MovimentoDoDia.VAZIO.com(PIX, PAGAMENTO, reais("40"), 1)
                .com(PIX_ONLINE, PAGAMENTO, reais("90"), 1).com(BOLETO, PAGAMENTO, reais("80"), 1);

        assertThat(dia.doBalcao().keySet()).containsExactly(DINHEIRO, PIX, CARTAO_DEBITO, CARTAO_CREDITO);
        assertThat(dia.semDinheiro().keySet()).containsExactly(PIX, CARTAO_DEBITO, CARTAO_CREDITO);
        assertThat(dia.porForma().get(PIX_ONLINE).recebido()).isEqualByComparingTo("90");
        assertThat(dia.porForma().get(BOLETO).recebido()).isEqualByComparingTo("80");
    }
}
