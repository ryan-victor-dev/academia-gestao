package br.com.academiagestao.pagamentos;

import br.com.academiagestao.pagamentos.ProvedorDePagamento.Forma;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A empresa de pagamentos ligada (ADR 0034), ou nenhuma (desligado): o que os services do
 * pagamento online perguntam antes de chamá-la. Separada do PagamentoOnlineService, que fazia tudo
 * (diagnóstico de 27/10, B6).
 */
@Component
class EmpresaDePagamento {

    private static final Logger log = LoggerFactory.getLogger(EmpresaDePagamento.class);

    private final Optional<ProvedorDePagamento> provedor;

    EmpresaDePagamento(ObjectProvider<ProvedorDePagamento> provedor) {
        this.provedor = Optional.ofNullable(provedor.getIfAvailable());
    }

    /** A ligada, ou vazio (desligado). */
    Optional<ProvedorDePagamento> talvez() {
        return provedor;
    }

    /** As formas que a empresa ligada oferece (nenhuma, desligado). */
    List<Forma> formasDaEmpresa() {
        return provedor.map(p -> Arrays.stream(Forma.values()).filter(p::oferece).toList()).orElse(List.of());
    }

    /** Ligada pelo ADMIN e oferecida pela empresa. */
    boolean disponivel(ConfiguracaoDoPagamentoOnline config, Forma forma) {
        return config.ligada(forma) && provedor.filter(p -> p.oferece(forma)).isPresent();
    }

    /** O nome da empresa ligada, ou {@code desligado}. */
    String provedorLigado() {
        return provedor.map(ProvedorDePagamento::nome).orElse(PagamentosProperties.DESLIGADO);
    }

    boolean simulado() {
        return provedor.filter(p -> p instanceof ProvedorSimulado).isPresent();
    }

    /** A ligada; desligado, recusa. */
    ProvedorDePagamento ligada() {
        return provedor.orElseThrow(() -> new PagamentoOnlineRecusadoException("O pagamento online está desligado"));
    }

    /** Cancela lá; se falhar, fica no log (a consulta dos encerrados tenta de novo, ADR 0036). */
    static void cancelarNaEmpresa(ProvedorDePagamento empresa, String idExterno) {
        try {
            empresa.cancelar(idExterno);
        } catch (RuntimeException e) {
            log.warn("Não foi possível cancelar na empresa {} o pagamento {}: {}", empresa.nome(), idExterno,
                    e.getMessage());
        }
    }
}
