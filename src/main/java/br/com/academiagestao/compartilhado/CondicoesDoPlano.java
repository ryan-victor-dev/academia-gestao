package br.com.academiagestao.compartilhado;

import java.math.BigDecimal;

/**
 * As condições de um plano, conferidas e normalizadas num lugar só (R3, revisão de
 * 2026-10-17). O plano as define e a matrícula as copia (ADRs 0013 e 0016): antes, cada um
 * tinha a sua cópia das regras e dos limites, e elas já tinham começado a divergir (o nome
 * do plano era limpo de um jeito no plano e de outro na matrícula). Fica no compartilhado
 * porque é o contrato entre os dois módulos, e a entidade da matrícula não depende do
 * módulo de planos (R2).
 *
 * @param nome            sem espaços nas pontas nem repetidos
 * @param valorMensal     com 2 casas
 * @param percentualMulta com 2 casas; só com fidelidade
 */
public record CondicoesDoPlano(String nome, BigDecimal valorMensal, int duracaoMeses, boolean fidelidade,
                               BigDecimal percentualMulta) {

    public static final int TAMANHO_MAXIMO_NOME = 80;
    public static final int DURACAO_MAXIMA_MESES = 60;
    // Teto decidido pelo dono (2026-10-20): o à vista de 60 meses (6 milhões) cabe na cobrança.
    // Em texto também, para as anotações da requisição (@DecimalMax só aceita texto constante).
    public static final String VALOR_MAXIMO_TEXTO = "100000.00";
    public static final BigDecimal VALOR_MAXIMO = new BigDecimal(VALOR_MAXIMO_TEXTO);
    public static final String PERCENTUAL_MAXIMO_MULTA_TEXTO = "100";
    public static final BigDecimal PERCENTUAL_MAXIMO_MULTA = new BigDecimal(PERCENTUAL_MAXIMO_MULTA_TEXTO);

    /** Confere tudo e devolve os valores normalizados; qualquer problema vira 400. */
    public static CondicoesDoPlano conferir(String nome, BigDecimal valorMensal, Integer duracaoMeses,
                                            Boolean fidelidade, BigDecimal percentualMulta) {
        String nomeLimpo = limparNome(nome);
        if (nomeLimpo.isEmpty()) {
            throw new DadosInvalidosException("nome é obrigatório");
        }
        if (nomeLimpo.length() > TAMANHO_MAXIMO_NOME) {
            throw new DadosInvalidosException("nome pode ter no máximo " + TAMANHO_MAXIMO_NOME + " caracteres");
        }
        if (valorMensal == null || valorMensal.signum() <= 0) {
            throw new DadosInvalidosException("valor mensal precisa ser maior que zero");
        }
        // 129.90 e 129.9 são o mesmo valor; 129.999 não existe em reais.
        if (valorMensal.stripTrailingZeros().scale() > 2) {
            throw new DadosInvalidosException("valor mensal pode ter no máximo 2 casas decimais");
        }
        if (valorMensal.compareTo(VALOR_MAXIMO) > 0) {
            throw new DadosInvalidosException("valor mensal pode ser no máximo " + VALOR_MAXIMO);
        }
        if (duracaoMeses == null || duracaoMeses < 1 || duracaoMeses > DURACAO_MAXIMA_MESES) {
            throw new DadosInvalidosException("duração precisa ser de 1 a " + DURACAO_MAXIMA_MESES + " meses");
        }
        boolean comFidelidade = Boolean.TRUE.equals(fidelidade);
        if (comFidelidade) {
            if (percentualMulta == null || percentualMulta.signum() <= 0
                    || percentualMulta.compareTo(PERCENTUAL_MAXIMO_MULTA) > 0) {
                throw new DadosInvalidosException("plano com fidelidade precisa de percentual de multa de 0,01 a 100");
            }
            if (percentualMulta.stripTrailingZeros().scale() > 2) {
                throw new DadosInvalidosException("percentual de multa pode ter no máximo 2 casas decimais");
            }
        } else if (percentualMulta != null) {
            throw new DadosInvalidosException("percentual de multa só existe em plano com fidelidade");
        }
        return new CondicoesDoPlano(nomeLimpo, valorMensal.setScale(2), duracaoMeses, comFidelidade,
                comFidelidade ? percentualMulta.setScale(2) : null);
    }

    /** Sem espaços nas pontas nem repetidos: "  Plano   Anual " vira "Plano Anual". */
    public static String limparNome(String nome) {
        return nome == null ? "" : nome.trim().replaceAll("\\s+", " ");
    }
}
