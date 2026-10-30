package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * O caixa do dia (ADR 0031): um para a academia por dia, aberto com o troco da gaveta e
 * fechado com a contagem do dinheiro. Sem caixa aberto, nada entra nem sai. O troco não se
 * corrige: um erro aparece como diferença no fechamento e vai na justificativa.
 *
 * O troco também é comparado com o que ficou na gaveta no fechamento anterior (contado −
 * retirado): a abertura continua às cegas e é aceita, e a diferença, só o ADMIN vê (ADR 0046,
 * item 8). Sem isso, declarar um troco menor do que ficou escondia uma sobra.
 */
public class Caixa {

    public enum Situacao { ABERTO, FECHADO }

    public static final BigDecimal TROCO_MAXIMO = new BigDecimal("100000");
    /** Só uma trava contra erro de digitação (cabe na coluna, NUMERIC(12,2)). */
    public static final BigDecimal CONTADO_MAXIMO = new BigDecimal("100000000");
    public static final int TAMANHO_MAXIMO_TEXTO = 500;

    private final UUID id;
    private final LocalDate dia;
    private final BigDecimal troco;
    private final BigDecimal trocoEsperado;
    private Situacao situacao;
    private final Instant abertoEm;
    private final UUID abertoPor;

    private Caixa(UUID id, LocalDate dia, BigDecimal troco, BigDecimal trocoEsperado, Situacao situacao,
                  Instant abertoEm, UUID abertoPor) {
        this.id = id;
        this.dia = dia;
        this.troco = troco;
        this.trocoEsperado = trocoEsperado;
        this.situacao = situacao;
        this.abertoEm = abertoEm;
        this.abertoPor = abertoPor;
    }

    /**
     * Abre o caixa do dia com o troco que está na gaveta. {@code trocoEsperado}: o que ficou na
     * gaveta no último fechamento do caixa anterior; nulo se não há caixa anterior.
     */
    public static Caixa abrir(LocalDate dia, BigDecimal troco, BigDecimal trocoEsperado, UUID por, Instant agora) {
        Objects.requireNonNull(dia, "o dia é obrigatório");
        Objects.requireNonNull(por, "quem abriu é obrigatório");
        if (troco == null || troco.signum() < 0 || troco.compareTo(TROCO_MAXIMO) > 0
                || troco.stripTrailingZeros().scale() > 2) {
            throw new CobrancaInvalidaException("o troco é obrigatório, de 0 a " + TROCO_MAXIMO
                    + " reais, em centavos");
        }
        return new Caixa(UUID.randomUUID(), dia, troco.setScale(2),
                trocoEsperado == null ? null : trocoEsperado.setScale(2), Situacao.ABERTO,
                agora.truncatedTo(ChronoUnit.MICROS), por);
    }

    /** Remonta um caixa lido do banco. */
    public static Caixa restaurar(UUID id, LocalDate dia, BigDecimal troco, BigDecimal trocoEsperado,
                                  Situacao situacao, Instant abertoEm, UUID abertoPor) {
        return new Caixa(id, dia, troco, trocoEsperado, situacao, abertoEm, abertoPor);
    }

    /**
     * Troco declarado − o que ficou na gaveta no fechamento anterior. Negativa: entrou menos do
     * que ficou (o que pode esconder uma sobra). Nula sem caixa anterior com que comparar.
     */
    public BigDecimal diferencaNaAbertura() {
        return trocoEsperado == null ? null : troco.subtract(trocoEsperado);
    }

    /**
     * A recepção vê o caixa de hoje e o dos dias ainda abertos (para fechá-los); os dias
     * fechados, com as diferenças e justificativas, só o ADMIN (decisão do dono).
     */
    public boolean recepcaoVe(LocalDate hoje) {
        return dia.equals(hoje) || situacao == Situacao.ABERTO;
    }

    /**
     * Contagem às cegas: com o caixa aberto, a recepção não vê o dinheiro recebido nem o
     * esperado (nem o de um fechamento anterior a uma reabertura); só depois de fechar vê a
     * diferença. Assim a contagem não é ajustada para bater (decisão do dono).
     */
    public boolean contagemAsCegas() {
        return situacao == Situacao.ABERTO;
    }

    /** Pagamento e estorno só entram no caixa aberto (decisão do dono). */
    public void conferirQueRecebe() {
        if (situacao != Situacao.ABERTO) {
            throw new OperacaoDeCaixaRecusadaException("O caixa de " + dia
                    + " está fechado: pagamento e estorno só depois que o ADMIN reabrir o dia");
        }
    }

    /**
     * Fecha com o dinheiro contado na gaveta. Esperado = troco + dinheiro recebido − dinheiro
     * estornado; diferença = contado − esperado. Diferente de zero, é aceita, mas exige uma
     * justificativa escrita (decisão do dono). {@code retirado}: o que sai da gaveta depois de
     * contar (nulo é zero); o resto é o troco esperado na abertura seguinte.
     */
    public Fechamento fechar(BigDecimal contado, BigDecimal retirado, BigDecimal recebidoEmDinheiro,
                             BigDecimal estornadoEmDinheiro, String justificativa, UUID por, Instant agora) {
        if (situacao != Situacao.ABERTO) {
            throw new OperacaoDeCaixaRecusadaException("O caixa de " + dia + " já está fechado");
        }
        Objects.requireNonNull(por, "quem fechou é obrigatório");
        if (contado == null || contado.signum() < 0 || contado.compareTo(CONTADO_MAXIMO) > 0
                || contado.stripTrailingZeros().scale() > 2) {
            throw new CobrancaInvalidaException("o dinheiro contado é obrigatório, de 0 a " + CONTADO_MAXIMO
                    + " reais, em centavos");
        }
        BigDecimal saiu = retirado == null ? BigDecimal.ZERO : retirado;
        if (saiu.signum() < 0 || saiu.compareTo(contado) > 0 || saiu.stripTrailingZeros().scale() > 2) {
            throw new CobrancaInvalidaException("o retirado vai de 0 até o dinheiro contado, em centavos");
        }
        BigDecimal esperado = troco.add(recebidoEmDinheiro).subtract(estornadoEmDinheiro).setScale(2);
        BigDecimal diferenca = contado.subtract(esperado).setScale(2);
        String texto = justificativa == null ? "" : justificativa.trim();
        if (texto.length() > TAMANHO_MAXIMO_TEXTO) {
            throw new CobrancaInvalidaException("a justificativa pode ter até " + TAMANHO_MAXIMO_TEXTO + " caracteres");
        }
        // Sem o valor na mensagem: com ele, quem conta descobriria o esperado (esperado = contado −
        // diferença) com um fechamento de propósito errado, e a contagem às cegas não valeria.
        if (diferenca.signum() != 0 && texto.isEmpty()) {
            throw new CobrancaInvalidaException("o contado difere do esperado: escreva uma justificativa");
        }
        this.situacao = Situacao.FECHADO;
        return new Fechamento(UUID.randomUUID(), id, contado.setScale(2), saiu.setScale(2), esperado, diferenca,
                texto.isEmpty() ? null : texto, agora.truncatedTo(ChronoUnit.MICROS), por);
    }

    /**
     * Só o ADMIN, com motivo (o controller confere o perfil). O fechamento anterior continua no
     * histórico; a recepção corrige e fecha de novo.
     */
    public Reabertura reabrir(String motivo, UUID por, Instant agora) {
        if (situacao != Situacao.FECHADO) {
            throw new OperacaoDeCaixaRecusadaException("O caixa de " + dia + " já está aberto");
        }
        Objects.requireNonNull(por, "quem reabriu é obrigatório");
        String limpo = motivo == null ? "" : motivo.trim();
        if (limpo.isEmpty() || limpo.length() > TAMANHO_MAXIMO_TEXTO) {
            throw new CobrancaInvalidaException("o motivo é obrigatório, com até " + TAMANHO_MAXIMO_TEXTO
                    + " caracteres");
        }
        this.situacao = Situacao.ABERTO;
        return new Reabertura(UUID.randomUUID(), id, limpo, agora.truncatedTo(ChronoUnit.MICROS), por);
    }

    public UUID getId() { return id; }
    public LocalDate getDia() { return dia; }
    public BigDecimal getTroco() { return troco; }
    public BigDecimal getTrocoEsperado() { return trocoEsperado; }
    public Situacao getSituacao() { return situacao; }
    public Instant getAbertoEm() { return abertoEm; }
    public UUID getAbertoPor() { return abertoPor; }

    /**
     * Um fechamento do caixa; cada um fica no histórico, mesmo depois de uma reabertura.
     * {@code contado − retirado} é o que fica na gaveta para o dia seguinte.
     */
    public record Fechamento(UUID id, UUID caixaId, BigDecimal contado, BigDecimal retirado, BigDecimal esperado,
                             BigDecimal diferenca, String justificativa, Instant em, UUID por) {
    }

    public record Reabertura(UUID id, UUID caixaId, String motivo, Instant em, UUID por) {
    }
}
