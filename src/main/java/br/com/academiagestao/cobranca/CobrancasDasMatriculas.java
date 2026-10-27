package br.com.academiagestao.cobranca;

import br.com.academiagestao.cobranca.dominio.Cobranca;
import br.com.academiagestao.cobranca.dominio.Cronograma;
import br.com.academiagestao.cobranca.dominio.MatriculaParaCobranca;
import br.com.academiagestao.cobranca.dominio.StatusCobranca;
import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Calendario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.COBRANCA;

/**
 * Porta pública da cobrança para as matrículas (ADR 0024): a cada mudança, a matrícula manda
 * os dados dela e as cobranças são recalculadas, na mesma transação.
 */
@Service
public class CobrancasDasMatriculas {

    private final CobrancaRepository repository;
    private final ConfiguracaoDaCobrancaRepository configuracao;
    private final Auditoria auditoria;
    private final Clock relogio;

    CobrancasDasMatriculas(CobrancaRepository repository, ConfiguracaoDaCobrancaRepository configuracao,
                           Auditoria auditoria, Clock relogio) {
        this.repository = repository;
        this.configuracao = configuracao;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * Tem cobrança atrasada há mais dias do que o tolerado (padrão 10, editável)? Então o
     * "pode treinar?" diz não (decisão do dono, ADR 0024). Vale para qualquer cobrança do
     * aluno, inclusive a multa de uma matrícula que já acabou.
     */
    @Transactional(readOnly = true)
    public boolean inadimplente(UUID alunoId) {
        int tolerados = configuracao.ler().regras().diasParaBloquear();
        LocalDate hoje = Calendario.hoje(relogio.instant());
        // Atrasada há mais de N dias = venceu antes de hoje - N.
        return repository.temEmAbertoVencidaAntesDe(alunoId, hoje.minusDays(tolerados));
    }

    /**
     * Acerta as cobranças da matrícula com o cronograma dela. Na primeira vez, registra desde
     * quando ela tem cobranças: o início, ou hoje para as que já existiam antes da cobrança
     * (decisão do dono: ninguém fica atrasado de repente). Quem chama trava a matrícula.
     *
     * Cada cobrança que o acerto muda (cancelada, reprogramada, fora do cronograma) vai para a
     * trilha, com o autor da mudança na matrícula (pendências baixas, item 18). As criadas não:
     * saem do cronograma da matrícula, que já está na trilha dela.
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
        List<Cobranca> gravadas = repository.travarDaMatricula(matricula.matriculaId());
        // O acerto altera as próprias cobranças: o retrato de antes precisa vir primeiro.
        Map<UUID, Map<String, Object>> antes = gravadas.stream()
                .collect(Collectors.toMap(Cobranca::getId, CobrancaService::retrato));
        Cronograma.Acerto acerto = Cronograma.acertar(matricula, gravadas, aPartirDe, agora);
        for (Cobranca alterada : acerto.alteradas()) {
            repository.atualizar(alterada);
            registrar(alterada, antes.get(alterada.getId()));
        }
        acerto.novas().forEach(repository::inserir);
    }

    private void registrar(Cobranca cobranca, Map<String, Object> antes) {
        Map<String, Object> depois = CobrancaService.retrato(cobranca);
        String acao;
        // O acerto só cancela as em aberto e nunca mexe numa cancelada.
        if (cobranca.getStatus() == StatusCobranca.CANCELADA) {
            acao = "CANCELADA";
            depois.put("motivo", cobranca.getCancelamento().motivo());
        } else if (!cobranca.getVencimento().toString().equals(antes.get("vencimento"))) {
            acao = "REPROGRAMADA";
        } else {
            acao = "FORA_DO_CRONOGRAMA";
        }
        auditoria.registrar(COBRANCA, cobranca.getId(), acao, antes, depois);
    }

    /**
     * Na troca de plano no dia {@code dia}: de onde contam as parcelas da matrícula nova, que
     * continua a antiga, e o crédito que ela leva (ADR 0026). Trava as cobranças da antiga: um
     * cancelamento ao mesmo tempo não muda a conta. Não grava nada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MatriculaParaCobranca.Continuacao continuacao(MatriculaParaCobranca antiga, LocalDate dia) {
        return Cronograma.continuacao(antiga, repository.travarDaMatricula(antiga.matriculaId()), dia);
    }

    /** Das matrículas informadas, as que ainda não têm cobranças no sistema. */
    @Transactional(readOnly = true)
    public Set<UUID> semCronograma(Collection<UUID> matriculaIds) {
        return repository.semCronograma(matriculaIds);
    }
}
