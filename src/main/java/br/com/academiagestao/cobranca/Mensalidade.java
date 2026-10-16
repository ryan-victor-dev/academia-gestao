package br.com.academiagestao.cobranca;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public class Mensalidade {

    private final UUID id;
    private final UUID matriculaId;
    private final BigDecimal valor;
    private final LocalDate vencimento;
    private StatusMensalidade status;
    private Instant pagaEm;

    private Mensalidade(UUID matriculaId, BigDecimal valor, LocalDate vencimento) {
        this.id = UUID.randomUUID();
        this.matriculaId = matriculaId;
        this.valor = valor;
        this.vencimento = vencimento;
        this.status = StatusMensalidade.PENDENTE;
    }

    public static Mensalidade nova(UUID matriculaId, BigDecimal valor, LocalDate vencimento) {
        Objects.requireNonNull(matriculaId, "matriculaId é obrigatório");
        Objects.requireNonNull(vencimento, "vencimento é obrigatório");
        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException("valor da mensalidade deve ser positivo");
        }
        return new Mensalidade(matriculaId, valor, vencimento);
    }

    public void pagar(Instant quando) {
        transicionarPara(StatusMensalidade.PAGA);
        this.pagaEm = Objects.requireNonNull(quando);
    }

    public void marcarVencida(LocalDate hoje) {
        if (!hoje.isAfter(vencimento)) {
            throw new IllegalStateException("Mensalidade ainda não venceu (vencimento " + vencimento + ")");
        }
        transicionarPara(StatusMensalidade.VENCIDA);
    }

    public void estornar() {
        transicionarPara(StatusMensalidade.ESTORNADA);
    }

    public void cancelar() {
        transicionarPara(StatusMensalidade.CANCELADA);
    }

    private void transicionarPara(StatusMensalidade destino) {
        if (!status.podeIrPara(destino)) {
            throw new TransicaoInvalidaException(status, destino);
        }
        this.status = destino;
    }

    public UUID getId() { return id; }
    public UUID getMatriculaId() { return matriculaId; }
    public BigDecimal getValor() { return valor; }
    public LocalDate getVencimento() { return vencimento; }
    public StatusMensalidade getStatus() { return status; }
    public Instant getPagaEm() { return pagaEm; }
}