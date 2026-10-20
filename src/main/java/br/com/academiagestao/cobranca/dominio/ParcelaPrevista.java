package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Uma cobrança que o cronograma da matrícula prevê. {@code (tipo, parcela)} a identifica. */
public record ParcelaPrevista(TipoCobranca tipo, int parcela, BigDecimal valor, LocalDate vencimento) {
}
