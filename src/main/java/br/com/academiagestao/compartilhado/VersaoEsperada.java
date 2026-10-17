package br.com.academiagestao.compartilhado;

import java.util.Arrays;

/**
 * Lê o cabeçalho If-Match (RFC 9110) e diz se ele aceita a versão atual do recurso.
 * - ausente: recusado (428), para nenhuma edição sobrescrever outra sem querer;
 * - "*": aceita qualquer versão (o cliente declara que quer sobrescrever);
 * - lista de ETags: aceita se alguma for igual à versão atual. ETag fraco (W/"...")
 *   nunca é aceito, porque If-Match exige comparação forte.
 */
public final class VersaoEsperada {

    private VersaoEsperada() {
    }

    public static String etag(long versao) {
        return "\"" + versao + "\"";
    }

    /** @param recurso nome do registro com artigo, para as mensagens: "o aluno", "o plano" */
    public static void conferir(String ifMatch, long versaoAtual, String recurso) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IfMatchAusenteException(recurso);
        }
        if (ifMatch.trim().equals("*")) {
            return;
        }
        boolean confere = Arrays.stream(ifMatch.split(","))
                .map(String::trim)
                .anyMatch(etag(versaoAtual)::equals);
        if (!confere) {
            throw new RecursoModificadoException(recurso);
        }
    }
}
