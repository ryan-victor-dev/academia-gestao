package br.com.academiagestao.noticias;

import org.springframework.core.io.AbstractResource;

import java.io.InputStream;
import java.util.UUID;

/**
 * O conteúdo de um anexo, lido do banco pedaço a pedaço (1 MB por vez, cada um numa consulta curta), sem carregar o
 * arquivo inteiro na memória. Como recurso do Spring, atende o Range (o vídeo começa a tocar sem baixar tudo, e
 * pula para o meio): o {@code skip} avança direto para o pedaço certo.
 */
class ConteudoDoAnexo extends AbstractResource {

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
        private byte[] parte = new byte[0];
        private int ordemDaParte = -1;

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
            int ordem = (int) (posicao / AnexoService.TAMANHO_DA_PARTE);
            if (ordem != ordemDaParte) {
                parte = anexos.parte(id, ordem).orElseThrow(() -> new IllegalStateException("pedaço " + ordem + " do anexo " + id + " sumiu"));
                ordemDaParte = ordem;
            }
            int dentro = (int) (posicao % AnexoService.TAMANHO_DA_PARTE);
            int lidos = Math.min(quantos, parte.length - dentro);
            System.arraycopy(parte, dentro, destino, inicio, lidos);
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
