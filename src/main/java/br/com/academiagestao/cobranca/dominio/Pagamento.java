package br.com.academiagestao.cobranca.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Um pagamento registrado, com as partes do devido naquele dia.
 *
 * @param codigo o código da transação (só no Pix e no cartão do balcão; nulo nos de antes, ADR 0046)
 */
public record Pagamento(BigDecimal acrescimo, BigDecimal juros, BigDecimal total, MeioDePagamento meio,
                        String codigo, Instant em, UUID por) {

    public static final int TAMANHO_MAXIMO_CODIGO = 100;

    /**
     * O código limpo: sem espaços e em maiúsculas, para "e2e 123" e "E2E123" serem o mesmo
     * comprovante (decisão do dono). Obrigatório no Pix e no cartão; nas outras formas, só vazio.
     */
    public static String codigoValido(MeioDePagamento meio, String informado) {
        String codigo = informado == null ? "" : informado.replaceAll("\\p{IsWhite_Space}", "").toUpperCase(Locale.ROOT);
        if (!meio.exigeCodigo()) {
            if (!codigo.isEmpty()) {
                throw new CobrancaInvalidaException("código da transação é só do Pix e do cartão");
            }
            return null;
        }
        if (codigo.isEmpty()) {
            throw new CobrancaInvalidaException("código da transação é obrigatório no Pix e no cartão");
        }
        if (codigo.codePointCount(0, codigo.length()) > TAMANHO_MAXIMO_CODIGO) {
            throw new CobrancaInvalidaException("código da transação deve ter no máximo "
                    + TAMANHO_MAXIMO_CODIGO + " caracteres");
        }
        if (codigo.codePoints().anyMatch(Character::isISOControl)) {
            throw new CobrancaInvalidaException("código da transação tem caracteres inválidos");
        }
        return codigo;
    }
}
