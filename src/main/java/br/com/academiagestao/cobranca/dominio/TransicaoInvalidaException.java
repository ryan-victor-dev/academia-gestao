package br.com.academiagestao.cobranca.dominio;

public class TransicaoInvalidaException extends RuntimeException {

    private final StatusCobranca origem;
    private final StatusCobranca destino;

    public TransicaoInvalidaException(StatusCobranca origem, StatusCobranca destino) {
        super("Transição de cobrança não permitida: %s -> %s".formatted(origem, destino));
        this.origem = origem;
        this.destino = destino;
    }

    public StatusCobranca getOrigem() { return origem; }
    public StatusCobranca getDestino() { return destino; }
}
