package br.com.academiagestao.pagamentos;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A porta para uma empresa de pagamentos (ADR 0034). O sistema só conhece esta interface: cada
 * empresa (Asaas, Mercado Pago, um banco com API...) é uma classe que a implementa, ligada pelo
 * {@code ACADEMIA_PAGAMENTOS_PROVEDOR}. Nada daqui fala de academia: serve para outro projeto.
 *
 * <p>As chamadas vão para fora (rede): nunca dentro de uma transação do banco. Falha de rede ou
 * da empresa vira {@link ProvedorIndisponivelException}.
 */
public interface ProvedorDePagamento {

    /** O nome no {@code .env} e na coluna {@code provedor}: muda a empresa, muda o nome. */
    String nome();

    /** Gera o Pix ou o boleto na empresa. */
    Gerado gerar(Pedido pedido);

    /** O que a empresa diz deste pagamento agora: é a única fonte de "foi pago". */
    Situacao consultar(String idExterno);

    /** Cancela na empresa um Pix ou boleto que não vale mais (melhor esforço: pode já ter sido pago). */
    void cancelar(String idExterno);

    /**
     * Confere a autenticidade de um aviso da empresa (token, assinatura) e devolve os ids
     * externos que ele cita. O corpo não é confiável para mais nada: o sistema consulta cada id.
     *
     * @throws AvisoRecusadoException aviso sem a prova de que veio da empresa
     */
    List<String> lerAviso(Map<String, String> cabecalhos, byte[] corpo);

    enum Forma { PIX, BOLETO }

    /** Quem paga: o boleto exige nome e CPF. */
    record Pagador(String nome, String cpf, String email) {
    }

    /**
     * @param referencia     o id do pagamento online aqui (a empresa devolve no aviso e no painel)
     * @param validoAte      até quando se paga (o Pix expira; o boleto vence no dia de {@code vencimento})
     * @param vencimento     o dia do vencimento do boleto (no Pix, o dia de {@code validoAte})
     */
    record Pedido(UUID referencia, Forma forma, BigDecimal valor, Instant validoAte, LocalDate vencimento,
                  String descricao, Pagador pagador) {
    }

    /** O que mostrar ao aluno: o Pix copia e cola, ou a linha digitável e o link do boleto. */
    record Gerado(String idExterno, String pixCopiaECola, String boletoLinhaDigitavel, String boletoUrl) {
    }

    enum Estado {
        AGUARDANDO,
        PAGO,
        /** Cancelado ou expirado sem pagamento. */
        ENCERRADO
    }

    /** {@code valorPago} e {@code pagoEm} só no PAGO. */
    record Situacao(Estado estado, BigDecimal valorPago, Instant pagoEm) {

        public static final Situacao AGUARDANDO = new Situacao(Estado.AGUARDANDO, null, null);
        public static final Situacao ENCERRADO = new Situacao(Estado.ENCERRADO, null, null);

        public static Situacao pago(BigDecimal valor, Instant em) {
            return new Situacao(Estado.PAGO, valor, em);
        }
    }
}
