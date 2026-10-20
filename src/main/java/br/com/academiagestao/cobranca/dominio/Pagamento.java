package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Um pagamento registrado no balcão, com as partes do devido naquele dia. */
public record Pagamento(BigDecimal acrescimo, BigDecimal juros, BigDecimal total, MeioDePagamento meio,
                        Instant em, UUID por) {
}
