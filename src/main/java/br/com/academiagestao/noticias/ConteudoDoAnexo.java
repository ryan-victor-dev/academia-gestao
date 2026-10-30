package br.com.academiagestao.noticias;

import org.springframework.core.io.AbstractResource;

import java.io.InputStream;
import java.util.UUID;

/**
 * O conteúdo de um anexo, lido do banco em trechos de {@value #TRECHO} bytes (cada um numa consulta curta), sem
 * carregar o arquivo inteiro na memória. O trecho fica abaixo da metade da região do G1 (1 MB num heap de até 2 GB):
 * com 1 MB por leitura, cada uma virava um objeto "gigante", e 20 downloads de 40 MB ao mesmo tempo levaram o
 * container a 958 MB de 1 GB (diagnóstico do ADR 0047). Como recurso do Spring, atende o Range (o vídeo começa a
 * tocar sem baixar tudo, e pula para o meio): o {@code skip} só muda a posição.
 */
class ConteudoDoAnexo extends AbstractResource {

    static final int TRECHO = 256 * 1024;

    private final Anexos anexos;
    private final Anexos.Anexo anexo;

    ConteudoDoAnexo(Anexos anexos, Anexos.Anexo anexo) {
        this.anexos = anexos;
        this.anexo = anexo;
    }

    @Override
    public String getDescription() {
        return "anexo " + anexo.id();
    }

    @Override
    public String getFilename() {
        return anexo.nome();
    }

    @Override
    public long contentLength() {
        return anexo.tamanho();
    }

    @Override
    public InputStream getInputStream() {
        return new Leitura(anexos, anexo.id(), anexo.tamanho());
    }

    private static final class Leitura extends InputStream {

        private final Anexos anexos;
        private final UUID id;
        private final long tamanho;
        private long posicao;
        private byte[] trecho = new byte[0];
        private long inicioDoTrecho = -1;

        Leitura(Anexos anexos, UUID id, long tamanho) {
            this.anexos = anexos;
            this.id = id;
            this.tamanho = tamanho;
        }

        @Override
        public int read() {
            byte[] um = new byte[1];
            return read(um, 0, 1) < 0 ? -1 : um[0] & 0xFF;
        }

        @Override
        public int read(byte[] destino, int inicio, int quantos) {
            if (posicao >= tamanho) {
                return -1;
            }
            if (quantos == 0) {
                return 0;
            }
            // A leitura só anda para a frente (o skip só avança): basta ver se passou do fim do trecho.
            if (inicioDoTrecho < 0 || posicao >= inicioDoTrecho + trecho.length) {
                int ordem = (int) (posicao / AnexoService.TAMANHO_DA_PARTE);
                int dentroDaParte = (int) (posicao % AnexoService.TAMANHO_DA_PARTE);
                trecho = anexos.trecho(id, ordem, dentroDaParte, TRECHO)
                        .filter(t -> t.length > 0)
                        .orElseThrow(() -> new IllegalStateException("pedaço " + ordem + " do anexo " + id + " sumiu"));
                inicioDoTrecho = posicao;
            }
            int dentro = (int) (posicao - inicioDoTrecho);
            int lidos = Math.min(quantos, trecho.length - dentro);
            System.arraycopy(trecho, dentro, destino, inicio, lidos);
            posicao += lidos;
            return lidos;
        }

        /** Pula sem ler: só muda a posição (o pedaço certo é buscado na próxima leitura). */
        @Override
        public long skip(long quantos) {
            long pulados = Math.max(0, Math.min(quantos, tamanho - posicao));
            posicao += pulados;
            return pulados;
        }

        @Override
        public int available() {
            return (int) Math.min(Integer.MAX_VALUE, tamanho - posicao);
        }
    }
}
