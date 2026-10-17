package br.com.academiagestao.matriculas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Pausa de uma matrícula (ADR 0018). O aluno fica parado de {@code inicio} até a véspera
 * da volta: a prevista, ou a registrada se voltou antes. Criado e alterado só pela
 * {@link Matricula}, que confere as regras e estende o fim.
 */
@Entity
@Table(name = "trancamentos")
public class Trancamento {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private LocalDate inicio;

    @Column(name = "volta_prevista", nullable = false, updatable = false)
    private LocalDate voltaPrevista;

    private LocalDate volta;

    @Column(nullable = false, updatable = false, length = Matricula.TAMANHO_MAXIMO_MOTIVO)
    private String motivo;

    @Column(name = "criado_por", nullable = false, updatable = false)
    private UUID criadoPor;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "volta_registrada_por")
    private UUID voltaRegistradaPor;

    // Exigido pelo JPA. Protegido para ninguém criar um Trancamento vazio pelo código.
    protected Trancamento() {
    }

    // Os dados já chegam conferidos pela Matricula.
    Trancamento(LocalDate inicio, LocalDate voltaPrevista, String motivo, UUID criadoPor, Instant criadoEm) {
        this.id = UUID.randomUUID();
        this.inicio = inicio;
        this.voltaPrevista = voltaPrevista;
        this.motivo = motivo;
        this.criadoPor = criadoPor;
        this.criadoEm = criadoEm.truncatedTo(ChronoUnit.MICROS);
    }

    /** Dia em que o aluno volta a treinar. */
    LocalDate voltaEfetiva() {
        return volta != null ? volta : voltaPrevista;
    }

    /** Dias parados: do início até a véspera da volta. */
    int dias() {
        return (int) ChronoUnit.DAYS.between(inicio, voltaEfetiva());
    }

    /** Parado neste dia? */
    boolean paradoEm(LocalDate dia) {
        return !dia.isBefore(inicio) && dia.isBefore(voltaEfetiva());
    }

    /** Ainda não terminou (em andamento ou agendado) neste dia? Um desfeito (0 dias) não conta. */
    boolean emAbertoEm(LocalDate dia) {
        return dias() > 0 && dia.isBefore(voltaEfetiva());
    }

    /**
     * Registra a volta no dia informado; antes do início, desfaz o trancamento (0 dias).
     * @return quantos dias deixaram de ser trancados
     */
    int voltarEm(LocalDate dia, UUID usuarioId) {
        int antes = dias();
        this.volta = dia.isBefore(inicio) ? inicio : dia;
        this.voltaRegistradaPor = usuarioId;
        return antes - dias();
    }

    public UUID getId() { return id; }
    public LocalDate getInicio() { return inicio; }
    public LocalDate getVoltaPrevista() { return voltaPrevista; }
    public LocalDate getVolta() { return volta; }
    public String getMotivo() { return motivo; }
    public UUID getCriadoPor() { return criadoPor; }
    public Instant getCriadoEm() { return criadoEm; }
    public UUID getVoltaRegistradaPor() { return voltaRegistradaPor; }
}
