package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Cronograma;
import br.com.academiagestao.cobranca.dominio.MatriculaParaCobranca;
import br.com.academiagestao.compartilhado.Calendario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Porta pública da cobrança para as matrículas (ADR 0024): a cada mudança, a matrícula manda
 * os dados dela e as cobranças são recalculadas, na mesma transação.
 */
@Service
public class CobrancasDasMatriculas {

    private final CobrancaRepository repository;
    private final Clock relogio;

    CobrancasDasMatriculas(CobrancaRepository repository, Clock relogio) {
        this.repository = repository;
        this.relogio = relogio;
    }

    /**
     * Acerta as cobranças da matrícula com o cronograma dela. Na primeira vez, registra desde
     * quando ela tem cobranças: o início, ou hoje para as que já existiam antes da cobrança
     * (decisão do dono: ninguém fica atrasado de repente). Quem chama trava a matrícula.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acompanhar(MatriculaParaCobranca matricula) {
        Instant agora = relogio.instant();
        LocalDate aPartirDe = repository.aPartirDe(matricula.matriculaId()).orElseGet(() -> {
            LocalDate hoje = Calendario.hoje(agora);
            LocalDate desde = matricula.inicio().isAfter(hoje) ? matricula.inicio() : hoje;
            repository.registrarCronograma(matricula.matriculaId(), desde);
            return desde;
        });
        Cronograma.Acerto acerto = Cronograma.acertar(matricula,
                repository.travarDaMatricula(matricula.matriculaId()), aPartirDe, agora);
        acerto.alteradas().forEach(repository::atualizar);
        acerto.novas().forEach(repository::inserir);
    }

    /** Das matrículas informadas, as que ainda não têm cobranças no sistema. */
    @Transactional(readOnly = true)
    public Set<UUID> semCronograma(Collection<UUID> matriculaIds) {
        return repository.semCronograma(matriculaIds);
    }
}
