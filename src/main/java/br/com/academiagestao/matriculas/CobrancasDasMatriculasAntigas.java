package br.com.academiagestao.matriculas;

import br.com.academiagestao.cobranca.CobrancasDasMatriculas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ao subir, gera as cobranças das matrículas que já existiam antes da cobrança (ADR 0024):
 * só as que vencem de hoje em diante. Depois da primeira vez, não sobra nenhuma: as novas
 * ganham as cobranças na própria transação. Fora da recuperação de ADMIN (sobe sem web); os
 * testes chamam {@link #gerar()} quando querem.
 * <p>
 * Desligada por padrão (ADR 0044, item 20): numa instalação nova não há matrícula de antes da
 * cobrança, e ela varreria todas as matrículas a cada subida para nada. Quem atualiza uma
 * instalação de antes da cobrança liga a propriedade numa subida.
 * <p>
 * Uma matrícula que falha fica sem cronograma e é tentada de novo na próxima subida: o erro
 * vai para o log, e a aplicação sobe mesmo assim. Uma exceção solta aqui derrubaria a subida,
 * e o reinício automático a repetiria sem fim (diagnóstico de 2026-10-20, A2).
 */
@Component
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "academia.cobranca.gerar-das-antigas-ao-subir", havingValue = "true")
class CobrancasDasMatriculasAntigas implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CobrancasDasMatriculasAntigas.class);
    private static final int LOTE = 500;

    private final MatriculaRepository repository;
    private final MatriculaService service;
    private final CobrancasDasMatriculas cobrancas;
    private final int lote;

    @Autowired
    CobrancasDasMatriculasAntigas(MatriculaRepository repository, MatriculaService service,
                                  CobrancasDasMatriculas cobrancas) {
        this(repository, service, cobrancas, LOTE);
    }

    CobrancasDasMatriculasAntigas(MatriculaRepository repository, MatriculaService service,
                                  CobrancasDasMatriculas cobrancas, int lote) {
        this.repository = repository;
        this.service = service;
        this.cobrancas = cobrancas;
        this.lote = lote;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        gerar();
    }

    /** Em lotes, cada matrícula na sua transação: uma com problema não desfaz nem impede as outras. */
    int gerar() {
        int geradas = 0;
        int falhas = 0;
        UUID depoisDe = new UUID(0L, 0L);
        List<UUID> lote;
        do {
            lote = repository.idsDepoisDe(depoisDe, PageRequest.ofSize(this.lote));
            Set<UUID> semCronograma = cobrancas.semCronograma(lote);
            for (UUID id : lote) {
                if (semCronograma.contains(id)) {
                    try {
                        service.acompanharCobranca(id);
                        geradas++;
                    } catch (RuntimeException e) {
                        falhas++;
                        log.error("Cobrança: a matrícula {} não ganhou as cobranças; fica para a próxima subida", id, e);
                    }
                }
            }
            depoisDe = lote.isEmpty() ? depoisDe : lote.getLast();
        } while (lote.size() == this.lote);
        if (geradas > 0) {
            log.info("Cobrança: {} matrícula(s) de antes da cobrança ganharam as cobranças de hoje em diante", geradas);
        }
        if (falhas > 0) {
            log.error("Cobrança: {} matrícula(s) de antes da cobrança ficaram sem cobranças (ver os erros acima)", falhas);
        }
        return geradas;
    }
}
