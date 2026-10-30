package br.com.academiagestao.compartilhado;

/**
 * Identidade de gênero e orientação sexual de um aluno ou de alguém da equipe (ADR 0047, decisão 2).
 * As duas são opcionais (nulo: a pessoa não respondeu), com "prefiro não informar" entre as opções e
 * "outra" para a pessoa descrever com as próprias palavras. Orientação sexual é dado pessoal sensível
 * (LGPD, art. 5º, II): só o ADMIN e a própria pessoa veem, e a trilha registra que mudou, sem os valores.
 * Java puro (ADR 0004).
 */
public record Identidade(IdentidadeDeGenero identidadeDeGenero, String identidadeDeGeneroOutra,
                         OrientacaoSexual orientacaoSexual, String orientacaoSexualOutra) {

    public static final int TAMANHO_MAXIMO_OUTRA = 50;

    /** Quem não respondeu nada. */
    public static final Identidade NAO_RESPONDIDA = new Identidade(null, null, null, null);

    public enum IdentidadeDeGenero {
        MULHER_CIS, HOMEM_CIS, MULHER_TRANS, HOMEM_TRANS, TRAVESTI, NAO_BINARIA, GENERO_FLUIDO, AGENERO,
        OUTRA, PREFIRO_NAO_INFORMAR
    }

    public enum OrientacaoSexual {
        HETEROSSEXUAL, LESBICA, GAY, BISSEXUAL, PANSEXUAL, ASSEXUAL, DEMISSEXUAL, QUEER,
        OUTRA, PREFIRO_NAO_INFORMAR
    }

    /**
     * A descrição só acompanha "outra", e é obrigatória nela; em branco conta como ausente. Sem os
     * espaços das pontas, até {@value #TAMANHO_MAXIMO_OUTRA} caracteres.
     */
    public Identidade {
        identidadeDeGeneroOutra = descricao("identidadeDeGeneroOutra",
                identidadeDeGenero == IdentidadeDeGenero.OUTRA, identidadeDeGeneroOutra);
        orientacaoSexualOutra = descricao("orientacaoSexualOutra",
                orientacaoSexual == OrientacaoSexual.OUTRA, orientacaoSexualOutra);
    }

    private static String descricao(String campo, boolean ehOutra, String valor) {
        String limpo = valor == null || valor.isBlank() ? null : valor.strip();
        if (ehOutra && limpo == null) {
            throw new DadosInvalidosException(campo + ": descreva a opção \"outra\"");
        }
        if (!ehOutra && limpo != null) {
            throw new DadosInvalidosException(campo + ": só acompanha a opção \"outra\"");
        }
        if (limpo != null && limpo.length() > TAMANHO_MAXIMO_OUTRA) {
            throw new DadosInvalidosException(campo + ": pode ter no máximo " + TAMANHO_MAXIMO_OUTRA + " caracteres");
        }
        return limpo;
    }
}
