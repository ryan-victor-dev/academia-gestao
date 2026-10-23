package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Quanto entrou e saiu num dia, em cada forma (ADR 0031): pagamentos menos estornos. O
 * estorno conta no dia em que foi feito, não no dia do pagamento.
 */
public record MovimentoDoDia(Map<MeioDePagamento, Movimento> porForma) {

    public static final MovimentoDoDia VAZIO = new MovimentoDoDia(Map.of());

    /** Toda forma aparece, com zero se não houve nada nela. */
    public MovimentoDoDia {
        Map<MeioDePagamento, Movimento> completo = new EnumMap<>(MeioDePagamento.class);
        for (MeioDePagamento meio : MeioDePagamento.values()) {
            completo.put(meio, porForma.getOrDefault(meio, Movimento.ZERO));
        }
        porForma = Collections.unmodifiableMap(completo);
    }

    /** Este dia com um pagamento ou estorno a mais (ou um grupo deles, já somado). */
    public MovimentoDoDia com(MeioDePagamento meio, Lancamento.Tipo tipo, BigDecimal valor, int quantos) {
        Map<MeioDePagamento, Movimento> novo = new EnumMap<>(porForma);
        Movimento grupo = tipo == Lancamento.Tipo.PAGAMENTO
                ? new Movimento(valor, quantos, Movimento.ZERO.estornado(), 0)
                : new Movimento(Movimento.ZERO.recebido(), 0, valor, quantos);
        novo.put(meio, novo.get(meio).mais(grupo));
        return new MovimentoDoDia(novo);
    }

    /** Os dois dias somados (o total de um período). */
    public MovimentoDoDia mais(MovimentoDoDia outro) {
        Map<MeioDePagamento, Movimento> soma = new EnumMap<>(MeioDePagamento.class);
        for (MeioDePagamento meio : MeioDePagamento.values()) {
            soma.put(meio, porForma.get(meio).mais(outro.porForma.get(meio)));
        }
        return new MovimentoDoDia(soma);
    }

    /** As formas que não passam pela gaveta: PIX e cartões. */
    public Map<MeioDePagamento, Movimento> semDinheiro() {
        Map<MeioDePagamento, Movimento> semDinheiro = new EnumMap<>(porForma);
        semDinheiro.remove(MeioDePagamento.DINHEIRO);
        return Collections.unmodifiableMap(semDinheiro);
    }

    public record Movimento(BigDecimal recebido, int pagamentos, BigDecimal estornado, int estornos) {

        public static final Movimento ZERO = new Movimento(new BigDecimal("0.00"), 0, new BigDecimal("0.00"), 0);

        public BigDecimal liquido() {
            return recebido.subtract(estornado);
        }

        public Movimento mais(Movimento outro) {
            return new Movimento(recebido.add(outro.recebido), pagamentos + outro.pagamentos,
                    estornado.add(outro.estornado), estornos + outro.estornos);
        }
    }
}
