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
        return new TextosDosAvisos.Fatura("Ana Paula Souza", "MENSALIDADE", 3, PRAZO, new BigDecimal("120.00"), devido, atraso, true);
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

    /** Quem saiu da academia só recebe os de atraso: sem o bloqueio (já saiu) e sem o app (o login só consulta). */
    @Test
    void aoInativoOAtrasoSemBloqueioESemOApp() {
        TextosDosAvisos.Fatura inativo = new TextosDosAvisos.Fatura("Ana Paula Souza", "MENSALIDADE", 3, PRAZO,
                new BigDecimal("120.00"), new BigDecimal("122.52"), 3, false);
        TextosDosAvisos.Texto t = TextosDosAvisos.email(Marco.ATRASADA_3, inativo, PADRAO);

        assertThat(t.assunto()).isEqualTo("Sua mensalidade 3 está atrasada há 3 dias");
        assertThat(t.corpo()).startsWith("Olá, Ana!")
                .contains("que venceu em 15/11/2026, está atrasada há 3 dias")
                .contains("O valor para pagar hoje é R$ 122,52 (era R$ 120,00)")
                .contains("O atraso aumenta o valor da fatura (acréscimo de 2% e juros de 1% ao mês).")
                .contains("Se você já pagou por boleto nos últimos dias, pode desconsiderar")
                .contains("Você pode pagar na recepção da academia.")
                .doesNotContain("bloque").doesNotContain("app").doesNotContain("Bons treinos");
        assertThat(TextosDosAvisos.sms(Marco.ATRASADA_3, inativo, PADRAO)).isEqualTo("Academia: Ana, sua mensalidade 3 está"
                + " atrasada há 3 dias. Hoje: R$ 122,52. O atraso aumenta o valor. Pague na recepção. Pagou por boleto há pouco?"
                + " Desconsidere.");
        // O ativo continua com o bloqueio e o app.
        assertThat(TextosDosAvisos.email(Marco.ATRASADA_3, fatura(new BigDecimal("122.52"), 3), PADRAO).corpo())
                .contains("bloqueia o acesso").contains("pelo app");
        assertThat(TextosDosAvisos.sms(Marco.ATRASADA_3, fatura(new BigDecimal("122.52"), 3), PADRAO)).contains("bloqueia o acesso");
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
        assertThat(TextosDosAvisos.nome(new TextosDosAvisos.Fatura("A", "MULTA", 1, PRAZO, BigDecimal.ONE, BigDecimal.ONE, 0, true)))
                .isEqualTo("multa por sair antes");
        assertThat(TextosDosAvisos.nome(new TextosDosAvisos.Fatura("A", "A_VISTA", 1, PRAZO, BigDecimal.ONE, BigDecimal.ONE, 0, true)))
                .isEqualTo("fatura do plano à vista");
        assertThat(TextosDosAvisos.primeiroNome("  Maria  Clara ")).isEqualTo("Maria");
        assertThat(TextosDosAvisos.primeiroNome(" ")).isEqualTo("tudo bem");
        assertThat(TextosDosAvisos.primeiroNome(null)).isEqualTo("tudo bem");
        assertThat(TextosDosAvisos.dinheiro(new BigDecimal("1234.5"))).isEqualTo("R$ 1.234,50");
        assertThat(TextosDosAvisos.dias(1)).isEqualTo("1 dia");
        assertThat(TextosDosAvisos.dias(0)).isEqualTo("0 dias");
    }

    @Test
    void smsCurtoComOEssencial() {
        String antes = TextosDosAvisos.sms(Marco.VENCE_EM_5, fatura(new BigDecimal("120.00"), 0), PADRAO);
        assertThat(antes).isEqualTo("Academia: oi, Ana! Sua mensalidade 3 de R$ 120,00 vence em 5 dias (15/11). Pague até lá sem "
                + "acréscimo e com o acesso liberado. O atraso aumenta o valor e bloqueia o acesso após 10 dias.");
        assertThat(TextosDosAvisos.sms(Marco.VENCE_HOJE, fatura(new BigDecimal("120.00"), 0), PADRAO))
                .contains("de R$ 120,00 vence hoje. Pague até lá");

        String atrasada = TextosDosAvisos.sms(Marco.ATRASADA_3, fatura(new BigDecimal("122.52"), 3), PADRAO);
        assertThat(atrasada).isEqualTo("Academia: Ana, sua mensalidade 3 está atrasada há 3 dias. Hoje: R$ 122,52. O atraso "
                + "aumenta o valor e bloqueia o acesso em 7 dias. Pagou por boleto há pouco? Desconsidere.");
        TextosDosAvisos.Regras curtas = new TextosDosAvisos.Regras(true, BigDecimal.ONE, BigDecimal.ONE, 3);
        assertThat(TextosDosAvisos.sms(Marco.ATRASADA_5, fatura(new BigDecimal("125"), 5), curtas)).contains("bloqueia o acesso (já bloqueado)");
        // No dia exato do bloqueio (faltam 0 dias), já bloqueado.
        assertThat(TextosDosAvisos.sms(Marco.ATRASADA_3, fatura(new BigDecimal("125"), 3), curtas)).contains("(já bloqueado)");
        assertThat(antes.length()).isLessThanOrEqualTo(200);
        assertThat(atrasada.length()).isLessThanOrEqualTo(200);
    }

    private static final java.time.Instant SABADO_9H = java.time.Instant.parse("2030-11-09T12:00:00Z");

    private static TextosDosAvisos.DaAcademia evento(java.time.Instant fim, String local) {
        return new TextosDosAvisos.DaAcademia("EVENTO", "Aulão de sábado", "Traga água.\nVagas limitadas.", SABADO_9H, fim, local);
    }

    @Test
    void emailDoEventoComQuandoOndeEComoDesligar() {
        TextosDosAvisos.Texto t = TextosDosAvisos.daAcademia(evento(SABADO_9H.plusSeconds(7200), "Sala 2"), "Rita Alves");

        assertThat(t.assunto()).isEqualTo("Evento da academia: Aulão de sábado");
        assertThat(t.corpo()).startsWith("Olá, Rita!\n\nTem evento novo na academia:\n\nAulão de sábado\n\n09/11/2030 às 09:00 até 11:00 · Sala 2\n")
                .contains("Traga água.\nVagas limitadas.").contains("Veja também na página inicial do app.")
                .endsWith("desligue em Meus dados, no app. Os lembretes de fatura continuam.");
    }

    @Test
    void emailDoComunicadoSemQuando() {
        TextosDosAvisos.Texto t = TextosDosAvisos.daAcademia(
                new TextosDosAvisos.DaAcademia("COMUNICADO", "Feriado", "Fechados na sexta.", null, null, null), "Rita");
        assertThat(t.assunto()).isEqualTo("Comunicado da academia: Feriado");
        assertThat(t.corpo()).startsWith("Olá, Rita!\n\nComunicado da academia:\n\nFeriado\n\nFechados na sexta.").doesNotContain(" às ");
    }

    @Test
    void quandoEOndeDoEvento() {
        assertThat(TextosDosAvisos.quandoEOnde(evento(null, null))).isEqualTo("09/11/2030 às 09:00");
        assertThat(TextosDosAvisos.quandoEOnde(evento(SABADO_9H.plusSeconds(86_400 + 3600), "Parque")))
                .isEqualTo("09/11/2030 às 09:00 até 10/11/2030 às 10:00 · Parque");
    }

    @Test
    void smsDaAcademiaCurto() {
        assertThat(TextosDosAvisos.daAcademiaSms(evento(null, "Sala 2"))).isEqualTo("Academia: evento: Aulão de sábado - 09/11 09:00, Sala 2. Veja no app.");
        assertThat(TextosDosAvisos.daAcademiaSms(evento(null, null))).isEqualTo("Academia: evento: Aulão de sábado - 09/11 09:00. Veja no app.");
        assertThat(TextosDosAvisos.daAcademiaSms(new TextosDosAvisos.DaAcademia("COMUNICADO", "Feriado", "x", null, null, null)))
                .isEqualTo("Academia: comunicado: Feriado. Veja no app.");
        String longo = TextosDosAvisos.daAcademiaSms(new TextosDosAvisos.DaAcademia("COMUNICADO", "a".repeat(150), "x", null, null, null));
        assertThat(longo).isEqualTo("Academia: comunicado: " + "a".repeat(79) + "…. Veja no app.");
        assertThat(TextosDosAvisos.daAcademiaSms(new TextosDosAvisos.DaAcademia("COMUNICADO", "a".repeat(80), "x", null, null, null)))
                .contains("a".repeat(80) + ". Veja");
    }
}
