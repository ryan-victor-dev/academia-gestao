package br.com.academiagestao.pagamentos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uma empresa de pagamentos de mentira (ADR 0034), para desenvolver e testar sem conta em
 * empresa nenhuma. Gera um Pix e um boleto que não pagam nada de verdade; o "pagamento" é o
 * botão de simular, que só existe com este provedor. Guarda tudo na memória: depois de
 * reiniciar, o que estava em aberto continua aguardando até expirar.
 *
 * <p>Nunca em produção: a mensalidade fica paga sem dinheiro nenhum. Por isso não sobe com um
 * domínio de verdade ({@code DOMINIO} no {@code .env}, diagnóstico de 27/10, M1): um {@code .env}
 * copiado do ambiente de teste não vira fraude.
 */
@Component
@ConditionalOnProperty(prefix = "academia.pagamentos", name = "provedor", havingValue = PagamentosProperties.SIMULADO)
class ProvedorSimulado implements ProvedorDePagamento {

    private static final Logger log = LoggerFactory.getLogger(ProvedorSimulado.class);
    static final String CABECALHO_DO_TOKEN = "x-aviso-token";
    static final String LINK_DO_CARTAO = "/cartao-simulado/";
    /** O "PDF do boleto" no simulado: uma tela do próprio app, que mostra o boleto com o aviso de não pagar. */
    static final String LINK_DO_BOLETO = "/boleto-simulado/";

    private record Registro(BigDecimal valor, Situacao situacao) {
    }

    private final Map<String, Registro> registros = new ConcurrentHashMap<>();
    private final Map<UUID, Devolucao> devolucoes = new ConcurrentHashMap<>();
    private final byte[] tokenDoAviso;
    private final Clock relogio;

    ProvedorSimulado(PagamentosProperties propriedades, @Value("${academia.dominio:}") String dominio,
                     Clock relogio) {
        TravaDoSimulado.conferir(dominio);
        String token = propriedades.tokenDoAvisoSimulado();
        this.tokenDoAviso = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
        this.relogio = relogio;
        log.warn("Pagamento online SIMULADO: nada é cobrado de verdade. Nunca use em produção.");
    }

    @Override
    public String nome() {
        return PagamentosProperties.SIMULADO;
    }

    /** Todas: serve para testar qualquer forma. */
    @Override
    public boolean oferece(Forma forma) {
        return true;
    }

    @Override
    public Gerado gerar(Pedido pedido) {
        String id = "sim_" + UUID.randomUUID();
        registros.put(id, new Registro(pedido.valor(), Situacao.AGUARDANDO));
        if (pedido.forma() == Forma.PIX) {
            return new Gerado(id, "PIX-SIMULADO|" + pedido.referencia() + "|" + pedido.valor(), null, null, null);
        }
        if (pedido.forma().cartao()) {
            // A "página da empresa" é uma tela do próprio app, só no simulado: não pede cartão nenhum.
            return new Gerado(id, null, null, null, LINK_DO_CARTAO + pedido.referencia());
        }
        // 47 dígitos, como uma linha digitável de verdade, mas começando com zeros.
        String digitos = String.format("%047d", Math.abs(pedido.referencia().getMostSignificantBits() % 1_000_000_000L));
        return new Gerado(id, null, digitos, LINK_DO_BOLETO + pedido.referencia(), null);
    }

    @Override
    public Situacao consultar(String idExterno) {
        Registro registro = registros.get(idExterno);
        return registro == null ? Situacao.AGUARDANDO : registro.situacao();
    }

    @Override
    public void cancelar(String idExterno) {
        registros.computeIfPresent(idExterno, (id, r) -> r.situacao().estado() == Estado.AGUARDANDO
                ? new Registro(r.valor(), Situacao.ENCERRADO) : r);
    }

    /**
     * Como a maioria das empresas: o Pix e o cartão (estorno na fatura) se devolvem pelo sistema;
     * o boleto, só por transferência.
     */
    @Override
    public boolean devolve(Forma forma) {
        return forma != Forma.BOLETO;
    }

    @Override
    public void devolver(Devolucao devolucao) {
        Registro registro = registros.get(devolucao.idExterno());
        if (registro == null || registro.situacao().estado() != Estado.PAGO) {
            // Depois de reiniciar, o simulado esquece tudo (ver a classe).
            throw new ProvedorIndisponivelException("o simulado não conhece este pagamento pago", null);
        }
        if (devolucao.valor().signum() <= 0 || devolucao.valor().compareTo(registro.situacao().valorPago()) > 0) {
            throw new IllegalArgumentException("devolução fora do valor pago: " + devolucao.valor());
        }
        devolucoes.putIfAbsent(devolucao.chave(), devolucao);
    }

    /** Só no simulado: o pagador contestou no banco (ADR 0037). Só um pago se contesta. */
    void simularContestacao(String idExterno) {
        registros.compute(idExterno, (id, r) -> {
            if (r == null || r.situacao().estado() != Estado.PAGO) {
                throw new IllegalArgumentException("só um pagamento simulado pago se contesta: " + idExterno);
            }
            return new Registro(r.valor(), Situacao.CONTESTADO);
        });
    }

    /** Quanto já foi devolvido deste pagamento (para os testes). */
    BigDecimal devolvido(String idExterno) {
        return devolucoes.values().stream().filter(d -> d.idExterno().equals(idExterno))
                .map(Devolucao::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** O aviso de teste: o corpo é só o id externo, e o cabeçalho traz o token configurado. */
    @Override
    public List<String> lerAviso(Map<String, String> cabecalhos, byte[] corpo) {
        String recebido = cabecalhos.get(CABECALHO_DO_TOKEN);
        if (tokenDoAviso == null || recebido == null
                || !MessageDigest.isEqual(tokenDoAviso, recebido.getBytes(StandardCharsets.UTF_8))) {
            throw new AvisoRecusadoException("aviso sem o token do simulado");
        }
        String id = new String(corpo, StandardCharsets.UTF_8).trim();
        return id.isEmpty() ? List.of() : List.of(id);
    }

    /** O "aluno pagou": com o valor gerado, ou com outro (para testar o dinheiro a devolver). */
    void simularPagamento(String idExterno, BigDecimal valor) {
        registros.compute(idExterno, (id, r) -> {
            if (r == null) {
                throw new IllegalArgumentException("pagamento simulado desconhecido: " + idExterno);
            }
            return new Registro(r.valor(), Situacao.pago(valor == null ? r.valor() : valor, relogio.instant()));
        });
    }
}
