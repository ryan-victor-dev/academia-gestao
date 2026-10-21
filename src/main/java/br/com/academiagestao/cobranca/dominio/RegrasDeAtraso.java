package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * A configuração da academia para atrasos (ADR 0024), editada pelo ADMIN e válida para
 * todas as cobranças no dia do pagamento.
 *
 * @param tipoAcrescimo     percentual do valor ou valor fixo em reais
 * @param acrescimo         cobrado uma vez, sempre limitado a 2% do valor (CDC, art. 52 §1º)
 * @param jurosAoMes        percentual ao mês, proporcional aos dias de atraso
 * @param diasParaBloquear  dias de atraso tolerados antes do "pode treinar?" dizer não
 */
public record RegrasDeAtraso(TipoAcrescimo tipoAcrescimo, BigDecimal acrescimo, BigDecimal jurosAoMes,
                             int diasParaBloquear) {

    public static final BigDecimal TETO_DO_ACRESCIMO_PERCENTUAL = new BigDecimal("2");
    public static final BigDecimal ACRESCIMO_MAXIMO_EM_REAIS = new BigDecimal("1000");
    public static final BigDecimal JUROS_MAXIMOS_AO_MES = new BigDecimal("10");
    public static final int DIAS_MAXIMOS_PARA_BLOQUEAR = 90;

    /** Os valores iniciais decididos pelo dono: 2% de acréscimo, 1% ao mês, 10 dias. */
    public static final RegrasDeAtraso PADRAO =
            new RegrasDeAtraso(TipoAcrescimo.PERCENTUAL, new BigDecimal("2.00"), new BigDecimal("1.00"), 10);

    private static final BigDecimal CEM = new BigDecimal("100");
    // Juros ao mês × dias / 30, em percentual: divide por 100 × 30.
    private static final BigDecimal CEM_VEZES_TRINTA = new BigDecimal("3000");

    public RegrasDeAtraso {
        if (tipoAcrescimo == null) {
            throw new CobrancaInvalidaException("tipo do acréscimo é obrigatório");
        }
        BigDecimal maximo = tipoAcrescimo == TipoAcrescimo.PERCENTUAL
                ? TETO_DO_ACRESCIMO_PERCENTUAL : ACRESCIMO_MAXIMO_EM_REAIS;
        if (acrescimo == null || acrescimo.signum() < 0 || acrescimo.compareTo(maximo) > 0
                || acrescimo.stripTrailingZeros().scale() > 2) {
            throw new CobrancaInvalidaException(tipoAcrescimo == TipoAcrescimo.PERCENTUAL
                    ? "acréscimo percentual vai de 0 a 2% (acima disso, o teto do CDC cortaria)"
                    : "acréscimo em reais vai de 0 a " + ACRESCIMO_MAXIMO_EM_REAIS + ", com até 2 casas");
        }
        if (jurosAoMes == null || jurosAoMes.signum() < 0 || jurosAoMes.compareTo(JUROS_MAXIMOS_AO_MES) > 0
                || jurosAoMes.stripTrailingZeros().scale() > 2) {
            throw new CobrancaInvalidaException("juros ao mês vão de 0 a " + JUROS_MAXIMOS_AO_MES
                    + "%, com até 2 casas");
        }
        if (diasParaBloquear < 0 || diasParaBloquear > DIAS_MAXIMOS_PARA_BLOQUEAR) {
            throw new CobrancaInvalidaException("dias para bloquear vão de 0 a " + DIAS_MAXIMOS_PARA_BLOQUEAR);
        }
        acrescimo = acrescimo.setScale(2);
        jurosAoMes = jurosAoMes.setScale(2);
    }

    /** O devido hoje por uma cobrança em aberto. Em dia (ou antes do vencimento): só o valor. */
    public ValorDevido devido(Cobranca cobranca, LocalDate hoje) {
        if (cobranca.getStatus() != StatusCobranca.PENDENTE) {
            throw new IllegalStateException("só cobrança pendente tem valor devido");
        }
        BigDecimal valor = cobranca.getValor();
        long dias = diasDeAtraso(cobranca, hoje);
        if (dias == 0) {
            return new ValorDevido(valor, zero(), zero(), 0);
        }
        BigDecimal configurado = tipoAcrescimo == TipoAcrescimo.PERCENTUAL
                ? valor.multiply(acrescimo).divide(CEM, 2, RoundingMode.HALF_UP)
                : acrescimo;
        BigDecimal teto = valor.multiply(TETO_DO_ACRESCIMO_PERCENTUAL).divide(CEM, 2, RoundingMode.HALF_UP);
        BigDecimal juros = valor.multiply(jurosAoMes).multiply(BigDecimal.valueOf(dias))
                .divide(CEM_VEZES_TRINTA, 2, RoundingMode.HALF_UP);
        return new ValorDevido(valor, configurado.min(teto), juros, dias);
    }

    /** Do 1º dia depois do prazo (o vencimento, ou o estorno) em diante (sem tolerância, ADR 0024). */
    public static long diasDeAtraso(Cobranca cobranca, LocalDate hoje) {
        if (cobranca.getStatus() != StatusCobranca.PENDENTE) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(cobranca.getPrazo(), hoje));
    }

    /** Atrasada há mais dias do que o tolerado: o "pode treinar?" diz não. */
    public boolean bloqueia(Cobranca cobranca, LocalDate hoje) {
        return diasDeAtraso(cobranca, hoje) > diasParaBloquear;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
