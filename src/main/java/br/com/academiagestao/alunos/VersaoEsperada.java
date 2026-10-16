package br.com.academiagestao.alunos;

import java.util.Arrays;

/**
 * Lê o cabeçalho If-Match (RFC 9110) e diz se ele aceita a versão atual do recurso.
 * - ausente: recusado (428), para nenhuma edição sobrescrever outra sem querer;
 * - "*": aceita qualquer versão (o cliente declara que quer sobrescrever);
 * - lista de ETags: aceita se alguma for igual à versão atual. ETag fraco (W/"...")
 *   nunca é aceito, porque If-Match exige comparação forte.
 */
final class VersaoEsperada {

    private VersaoEsperada() {
    }

    static String etag(long versao) {
        return "\"" + versao + "\"";
    }

    static void conferir(String ifMatch, long versaoAtual) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IfMatchAusenteException();
        }
        if (ifMatch.trim().equals("*")) {
            return;
        }
        boolean confere = Arrays.stream(ifMatch.split(","))
                .map(String::trim)
                .anyMatch(etag(versaoAtual)::equals);
        if (!confere) {
            throw new AlunoModificadoException();
        }
    }
}
