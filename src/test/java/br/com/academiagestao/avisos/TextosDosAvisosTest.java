package br.com.academiagestao.avisos;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os textos dos avisos (ADR 0047): antes do vencimento, amigável e dizendo o que acontece se atrasar; em atraso,
 * o valor de hoje, o aumento e o bloqueio. O que o dono pediu está nos dois.
 */
class TextosDosAvisosTest {

    private static final TextosDosAvisos.Regras PADRAO = new TextosDosAvisos.Regras(true, new BigDecimal("2.00"), new BigDecimal("1.00"), 10);
    private static final LocalDate PRAZO = LocalDate.of(2026, 11, 15);

    private static TextosDosAvisos.Fatura fatura(BigDecimal devido, long atraso) {
        return new TextosDosAvisos.Fatura("Ana Paula Souza", "MENSALIDADE", 3, PRAZO, new BigDecimal("120.00"), devido, atraso);
    }

    @Test
    void antesDoVencimentoAmigavelEAvisaDoAumentoEDoBloqueio() {
        TextosDosAvisos.Texto t = TextosDosAvisos.email(Marco.VENCE_EM_5, fatura(new BigDecimal("120.00"), 0), PADRAO);

        assertThat(t.assunto()).isEqualTo("Lembrete amigo: sua mensalidade 3 vence em 5 dias");
        assertThat(t.corpo()).startsWith("Olá, Ana!\n\n")
                .contains("boa notícia: ainda dá tempo de pagar a sua mensalidade 3 sem nenhum acréscimo")
                .contains("vence em 5 dias, no dia 15/11/2026, no valor de R$ 120,00")
                .contains("o seu acesso à academia continua liberado")
                .contains("se o pagamento atrasar, o valor aumenta (acréscimo de 2% e juros de 1% ao mês)")
                .contains("depois de 10 dias de atraso, o acesso à academia é bloqueado até o pagamento")
                .contains("em Pagamentos").doesNotContain("atrasada").doesNotContain("boleto");
        assertThat(TextosDosAvisos.email(Marco.VENCE_EM_1, fatura(new BigDecimal("120.00"), 0), PADRAO).corpo())
                .contains("vence em 1 dia, no dia 15/11/2026");
    }

    @Test
    void noDiaEhOUltimoDiaSemAcrescimo() {
        TextosDosAvisos.Texto t = TextosDosAvisos.email(Marco.VENCE_HOJE, fatura(new BigDecimal("120.00"), 0), PADRAO);

        assertThat(t.assunto()).isEqualTo("Hoje é o dia: pague sua mensalidade 3 sem acréscimo");
        assertThat(t.corpo()).contains("Boa notícia: hoje ainda dá para pagar a sua mensalidade 3 sem nenhum acréscimo")
                .contains("vence hoje (15/11/2026)").contains("o valor aumenta").contains("bloqueado");
    }

    @Test
    void emAtrasoOValorDeHojeOAumentoEOBloqueio() {
        TextosDosAvisos.Texto t = TextosDosAvisos.email(Marco.ATRASADA_3, fatura(new BigDecimal("122.52"), 3), PADRAO);

        assertThat(t.assunto()).isEqualTo("Sua mensalidade 3 está atrasada há 3 dias");
        assertThat(t.corpo()).startsWith("Olá, Ana!")
                .contains("que venceu em 15/11/2026, está atrasada há 3 dias")
                .contains("O valor para pagar hoje é R$ 122,52 (era R$ 120,00)")
                .contains("O atraso aumenta o valor da fatura (acréscimo de 2% e juros de 1% ao mês) e bloqueia o acesso à academia")
                .contains("o bloqueio começa com 10 dias de atraso, daqui a 7 dias")
                .contains("Se você já pagou por boleto nos últimos dias, pode desconsiderar");
        assertThat(TextosDosAvisos.email(Marco.ATRASADA_1, fatura(new BigDecimal("121"), 1), PADRAO).assunto())
                .isEqualTo("Sua mensalidade 3 está atrasada há 1 dia");
    }

    @Test
    void comOBloqueioJaValendoDizQueEstaBloqueado() {
        TextosDosAvisos.Regras curtas = new TextosDosAvisos.Regras(true, new BigDecimal("2"), new BigDecimal("1"), 3);
        assertThat(TextosDosAvisos.email(Marco.ATRASADA_3, fatura(new BigDecimal("122"), 3), curtas).corpo())
                .contains("o acesso já está bloqueado até o pagamento").doesNotContain("daqui a");
        assertThat(TextosDosAvisos.email(Marco.ATRASADA_1, fatura(new BigDecimal("121"), 1), curtas).corpo())
                .contains("daqui a 2 dias");
    }

    @Test
    void custoDoAtrasoConformeAsRegras() {
        assertThat(TextosDosAvisos.custoDoAtraso(new TextosDosAvisos.Regras(false, new BigDecimal("15.00"), new BigDecimal("0.5"), 10)))
                .isEqualTo("acréscimo de R$ 15,00 e juros de 0,5% ao mês");
        assertThat(TextosDosAvisos.custoDoAtraso(new TextosDosAvisos.Regras(true, BigDecimal.ZERO, new BigDecimal("1"), 10)))
                .isEqualTo("juros de 1% ao mês");
        assertThat(TextosDosAvisos.custoDoAtraso(new TextosDosAvisos.Regras(true, new BigDecimal("2"), BigDecimal.ZERO, 10)))
                .isEqualTo("acréscimo de 2%");
        assertThat(TextosDosAvisos.custoDoAtraso(new TextosDosAvisos.Regras(true, BigDecimal.ZERO, BigDecimal.ZERO, 10)))
                .isEqualTo("conforme as regras da academia");
    }

    @Test
    void nomesDasFaturasEDetalhes() {
        assertThat(TextosDosAvisos.nome(new TextosDosAvisos.Fatura("A", "MULTA", 1, PRAZO, BigDecimal.ONE, BigDecimal.ONE, 0)))
                .isEqualTo("multa por sair antes");
        assertThat(TextosDosAvisos.nome(new TextosDosAvisos.Fatura("A", "A_VISTA", 1, PRAZO, BigDecimal.ONE, BigDecimal.ONE, 0)))
                .isEqualTo("fatura do plano à vista");
        assertThat(TextosDosAvisos.primeiroNome("  Maria  Clara ")).isEqualTo("Maria");
        assertThat(TextosDosAvisos.primeiroNome(" ")).isEqualTo("tudo bem");
        assertThat(TextosDosAvisos.primeiroNome(null)).isEqualTo("tudo bem");
        assertThat(TextosDosAvisos.dinheiro(new BigDecimal("1234.5"))).isEqualTo("R$ 1.234,50");
        assertThat(TextosDosAvisos.dias(1)).isEqualTo("1 dia");
        assertThat(TextosDosAvisos.dias(0)).isEqualTo("0 dias");
    }
}
