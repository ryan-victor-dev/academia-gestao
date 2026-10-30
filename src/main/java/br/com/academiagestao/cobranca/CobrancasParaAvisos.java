package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.RegrasDeAtraso;
import br.com.academiagestao.cobranca.dominio.TipoAcrescimo;
import br.com.academiagestao.cobranca.dominio.ValorDevido;
import br.com.academiagestao.compartilhado.Calendario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Porta pública da cobrança para os avisos de fatura (ADR 0047): as faturas em aberto que vencem (ou venceram) em
 * certos dias, com o devido de hoje, e as regras do atraso para o texto do aviso. Só tipos simples saem daqui.
 */
@Service
public class CobrancasParaAvisos {

    /**
     * Uma fatura em aberto. {@code prazo}: até quando se paga sem atraso; {@code devidoHoje}: com acréscimo e juros,
     * se já atrasou.
     */
    public record FaturaEmAberto(UUID id, UUID alunoId, String tipo, int parcela, LocalDate prazo, BigDecimal valor,
                                 BigDecimal devidoHoje, long diasDeAtraso) {
    }

    /** O que o atraso custa: acréscimo (percentual ou em reais), juros ao mês e os dias até o bloqueio. */
    public record RegrasDoAtraso(boolean acrescimoPercentual, BigDecimal acrescimo, BigDecimal jurosAoMes,
                                 int diasParaBloquear) {
    }

    private final CobrancaRepository repository;
    private final ConfiguracaoDaCobrancaRepository configuracao;
    private final Clock relogio;

    CobrancasParaAvisos(CobrancaRepository repository, ConfiguracaoDaCobrancaRepository configuracao, Clock relogio) {
        this.repository = repository;
        this.configuracao = configuracao;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public List<FaturaEmAberto> emAbertoComPrazoEm(Collection<LocalDate> prazos) {
        RegrasDeAtraso regras = configuracao.ler().regras();
        LocalDate hoje = Calendario.hoje(relogio.instant());
        return repository.emAbertoComPrazoEm(prazos).stream().map(c -> fatura(c, regras.devido(c, hoje))).toList();
    }

    @Transactional(readOnly = true)
    public RegrasDoAtraso regras() {
        RegrasDeAtraso r = configuracao.ler().regras();
        return new RegrasDoAtraso(r.tipoAcrescimo() == TipoAcrescimo.PERCENTUAL, r.acrescimo(), r.jurosAoMes(),
                r.diasParaBloquear());
    }

    /**
     * Na transação de quem chama: trava a fatura para leitura até o fim dela e diz se ela continua em aberto. Quem
     * pagou nesse meio tempo não recebe o aviso (o pagamento espera, ou acontece antes e a fatura já não está em aberto).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean continuaEmAberto(UUID faturaId) {
        return repository.continuaEmAberto(faturaId);
    }

    private static FaturaEmAberto fatura(Cobranca c, ValorDevido devido) {
        return new FaturaEmAberto(c.getId(), c.getAlunoId(), c.getTipo().name(), c.getParcela(), c.getPrazo(), c.getValor(),
                devido.total(), devido.diasDeAtraso());
    }
}
