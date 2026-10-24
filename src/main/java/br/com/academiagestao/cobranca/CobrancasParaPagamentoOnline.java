package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.MeioDePagamento;
import br.com.academiagestao.cobranca.dominio.StatusCobranca;
import br.com.academiagestao.cobranca.dominio.ValorDevido;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Porta pública da cobrança para o pagamento online (ADR 0034): o que o aluno tem em aberto,
 * com o valor de hoje, e o registro do que a empresa de pagamentos confirmou. Só tipos simples
 * saem daqui: o módulo de pagamentos não conhece o domínio da cobrança.
 */
@Service
public class CobrancasParaPagamentoOnline {

    public enum FormaOnline { PIX, BOLETO, CREDITO, DEBITO }

    public enum ResultadoDoRecebimento {
        /** A cobrança ficou paga. */
        RECEBIDO,
        /** Já estava paga ou cancelada: o dinheiro é a devolver. */
        NAO_ESTA_EM_ABERTO,
        /** Chegou outro valor, não o combinado: o dinheiro é a devolver. */
        VALOR_DIFERENTE
    }

    /** O valor combinado com o aluno, em partes: o valor da cobrança, o acréscimo e os juros. */
    public record ValorCombinado(BigDecimal valor, BigDecimal acrescimo, BigDecimal juros) {

        public BigDecimal total() {
            return valor.add(acrescimo).add(juros);
        }
    }

    /**
     * Uma cobrança em aberto do aluno, com o devido hoje.
     *
     * @param vencimento até quando se paga sem atraso (o prazo, que muda com um estorno, ADR 0026)
     */
    public record CobrancaEmAberto(UUID id, String tipo, int parcela, LocalDate vencimento, ValorCombinado devido,
                                   long diasDeAtraso) {
    }

    private final CobrancaService service;

    CobrancasParaPagamentoOnline(CobrancaService service) {
        this.service = service;
    }

    /** As em aberto do aluno, da que vence primeiro para a última. */
    @Transactional(readOnly = true)
    public List<CobrancaEmAberto> emAberto(UUID alunoId) {
        return service.doAluno(alunoId).stream()
                .filter(c -> c.cobranca().getStatus() == StatusCobranca.PENDENTE)
                .map(c -> {
                    ValorDevido d = c.devido().orElseThrow();
                    return new CobrancaEmAberto(c.cobranca().getId(), c.cobranca().getTipo().name(),
                            c.cobranca().getParcela(), c.cobranca().getPrazo(),
                            new ValorCombinado(d.valor(), d.acrescimo(), d.juros()), d.diasDeAtraso());
                })
                .toList();
    }

    /**
     * Registra o que a empresa confirmou. Na transação de quem chama: a linha do pagamento
     * online é travada antes da cobrança (ADR 0034).
     */
    @Transactional
    public ResultadoDoRecebimento receber(UUID cobrancaId, ValorCombinado combinado, BigDecimal recebido,
                                          FormaOnline forma, UUID por) {
        ValorDevido devido = new ValorDevido(combinado.valor(), combinado.acrescimo(), combinado.juros(), 0);
        MeioDePagamento meio = switch (forma) {
            case PIX -> MeioDePagamento.PIX_ONLINE;
            case BOLETO -> MeioDePagamento.BOLETO;
            case CREDITO -> MeioDePagamento.CREDITO_ONLINE;
            case DEBITO -> MeioDePagamento.DEBITO_ONLINE;
        };
        return switch (service.receberOnline(cobrancaId, devido, recebido, meio, por)) {
            case RECEBIDO -> ResultadoDoRecebimento.RECEBIDO;
            case NAO_ESTA_EM_ABERTO -> ResultadoDoRecebimento.NAO_ESTA_EM_ABERTO;
            case VALOR_DIFERENTE -> ResultadoDoRecebimento.VALOR_DIFERENTE;
        };
    }
}
