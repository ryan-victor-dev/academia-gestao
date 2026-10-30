package br.com.academiagestao.noticias;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Os arquivos aceitos nos anexos (ADR 0047, decisão 6), reconhecidos pelos primeiros bytes do conteúdo, e nunca
 * pelo nome nem pelo tipo que o navegador declarou: um HTML com nome de "foto.jpg" não passa. SVG fica de fora
 * (pode levar script). Cada grupo tem o seu teto: foto 5 MB, documento 10 MB, vídeo 50 MB. Java puro.
 */
enum TipoDeArquivo {
    JPEG(Grupo.IMAGEM, "image/jpeg"),
    PNG(Grupo.IMAGEM, "image/png"),
    WEBP(Grupo.IMAGEM, "image/webp"),
    GIF(Grupo.IMAGEM, "image/gif"),
    MP4(Grupo.VIDEO, "video/mp4"),
    WEBM(Grupo.VIDEO, "video/webm"),
    PDF(Grupo.DOCUMENTO, "application/pdf");

    static final int BYTES_PARA_RECONHECER = 12;
    private static final long MB = 1024 * 1024;

    enum Grupo {
        IMAGEM(5 * MB), VIDEO(50 * MB), DOCUMENTO(10 * MB);

        private final long tamanhoMaximo;

        Grupo(long tamanhoMaximo) {
            this.tamanhoMaximo = tamanhoMaximo;
        }

        long tamanhoMaximo() {
            return tamanhoMaximo;
        }
    }

    private final Grupo grupo;
    private final String conteudoTipo;

    TipoDeArquivo(Grupo grupo, String conteudoTipo) {
        this.grupo = grupo;
        this.conteudoTipo = conteudoTipo;
    }

    Grupo grupo() {
        return grupo;
    }

    String conteudoTipo() {
        return conteudoTipo;
    }

    static TipoDeArquivo doConteudoTipo(String conteudoTipo) {
        return Arrays.stream(values()).filter(t -> t.conteudoTipo.equals(conteudoTipo)).findFirst().orElseThrow();
    }

    /** Pelo começo do arquivo; vazio se não for nenhum dos aceitos. */
    static Optional<TipoDeArquivo> reconhecer(byte[] inicio) {
        if (comeca(inicio, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (comeca(inicio, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (texto(inicio, 0, "RIFF") && texto(inicio, 8, "WEBP")) {
            return Optional.of(WEBP);
        }
        if (texto(inicio, 0, "GIF87a") || texto(inicio, 0, "GIF89a")) {
            return Optional.of(GIF);
        }
        // MP4 (e o MOV do celular, que o navegador toca como MP4): a caixa "ftyp" logo no começo.
        if (texto(inicio, 4, "ftyp")) {
            return Optional.of(MP4);
        }
        if (comeca(inicio, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return Optional.of(WEBM);
        }
        if (texto(inicio, 0, "%PDF-")) {
            return Optional.of(PDF);
        }
        return Optional.empty();
    }

    private static boolean texto(byte[] dados, int posicao, String esperado) {
        return comeca(dados, posicao, esperado.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean comeca(byte[] dados, int posicao, int... esperado) {
        byte[] bytes = new byte[esperado.length];
        for (int i = 0; i < esperado.length; i++) {
            bytes[i] = (byte) esperado[i];
        }
        return comeca(dados, posicao, bytes);
    }

    private static boolean comeca(byte[] dados, int posicao, byte[] esperado) {
        if (dados.length < posicao + esperado.length) {
            return false;
        }
        return Arrays.equals(dados, posicao, posicao + esperado.length, esperado, 0, esperado.length);
    }
}
