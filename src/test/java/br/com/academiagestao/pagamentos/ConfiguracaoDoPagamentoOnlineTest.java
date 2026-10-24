package br.com.academiagestao.pagamentos;

import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.CobrancaEmAberto;
import br.com.academiagestao.cobranca.CobrancasParaPagamentoOnline.ValorCombinado;
import br.com.academiagestao.pagamentos.ConfiguracaoDoPagamentoOnline.Adiantar;
import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Até onde o aluno adianta, e os limites da configuração (ADR 0034). */
class ConfiguracaoDoPagamentoOnlineTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 10);

    private static CobrancaEmAberto vencendo(LocalDate dia) {
        return new CobrancaEmAberto(UUID.randomUUID(), "MENSALIDADE", 1, dia,
                new ValorCombinado(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO), 0);
    }

    private static ConfiguracaoDoPagamentoOnline config(Adiantar adiantar) {
        return new ConfiguracaoDoPagamentoOnline(true, false, adiantar, 3, 0);
    }

    private final CobrancaEmAberto atrasada = vencendo(HOJE.minusDays(20));
    private final CobrancaEmAberto deHoje = vencendo(HOJE);
    private final CobrancaEmAberto proxima = vencendo(HOJE.plusDays(1));
    private final CobrancaEmAberto depois = vencendo(HOJE.plusMonths(1));
    private final List<CobrancaEmAberto> todas = List.of(atrasada, deHoje, proxima, depois);

    @Test
    void nenhumaSoAsVencidasEADeHoje() {
        assertThat(config(Adiantar.NENHUMA).pagaveis(todas, HOJE)).containsExactly(atrasada, deHoje);
    }

    @Test
    void proximaMaisAPrimeiraAVencer() {
        assertThat(config(Adiantar.PROXIMA).pagaveis(todas, HOJE)).containsExactly(atrasada, deHoje, proxima);
        assertThat(config(Adiantar.PROXIMA).pagaveis(List.of(proxima, depois), HOJE)).containsExactly(proxima);
    }

    @Test
    void todasAsEmAberto() {
        assertThat(config(Adiantar.TODAS).pagaveis(todas, HOJE)).containsExactlyElementsOf(todas);
    }

    @Test
    void formaLigada() {
        assertThat(config(Adiantar.NENHUMA).ligada(Forma.PIX)).isTrue();
        assertThat(config(Adiantar.NENHUMA).ligada(Forma.BOLETO)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31, -1})
    void diasDoBoletoDe1A30(int dias) {
        assertThatThrownBy(() -> new ConfiguracaoDoPagamentoOnline(true, true, Adiantar.PROXIMA, dias, 0))
                .isInstanceOf(PagamentoOnlineInvalidoException.class).hasMessageContaining("1 a 30");
        assertThat(new ConfiguracaoDoPagamentoOnline(true, true, Adiantar.PROXIMA, 30, 0).diasDoBoleto()).isEqualTo(30);
        assertThat(new ConfiguracaoDoPagamentoOnline(true, true, Adiantar.PROXIMA, 1, 0).diasDoBoleto()).isEqualTo(1);
    }

    @Test
    void adiantarObrigatorio() {
        assertThatThrownBy(() -> new ConfiguracaoDoPagamentoOnline(true, true, null, 3, 0))
                .isInstanceOf(PagamentoOnlineInvalidoException.class);
    }
}
