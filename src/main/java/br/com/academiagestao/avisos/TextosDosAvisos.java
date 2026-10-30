package br.com.academiagestao.avisos;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Os textos dos avisos de fatura (ADR 0047, decisão 7). Antes do vencimento, em tom amigável, como quem adianta uma
 * coisa boa: ainda dá para pagar sem acréscimo e com o acesso liberado; e o que acontece se atrasar. Em atraso, direto:
 * o valor de hoje, que o atraso aumenta o valor e que bloqueia o acesso. Texto simples (o e-mail não interpreta HTML).
 * Java puro.
 */
final class TextosDosAvisos {

    private static final Locale BRASIL = Locale.of("pt", "BR");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** O que o texto precisa saber da fatura e das regras do atraso. */
    record Fatura(String nomeDoAluno, String tipo, int parcela, LocalDate prazo, BigDecimal valor, BigDecimal devidoHoje,
                  long diasDeAtraso) {
    }

    record Regras(boolean acrescimoPercentual, BigDecimal acrescimo, BigDecimal jurosAoMes, int diasParaBloquear) {
    }

    record Texto(String assunto, String corpo) {
    }

    private TextosDosAvisos() {
    }

    static Texto email(Marco marco, Fatura f, Regras r) {
        String ola = "Olá, " + primeiroNome(f.nomeDoAluno()) + "!\n\n";
        String fim = "\n\nVocê pode pagar na recepção ou pelo app da academia, em Pagamentos.\n\nBons treinos!\nAcademia";
        if (!marco.atrasada()) {
            String quando = marco == Marco.VENCE_HOJE ? "vence hoje (" + data(f.prazo()) + ")"
                    : "vence em " + dias(-marco.diasDepoisDoPrazo()) + ", no dia " + data(f.prazo());
            String assunto = marco == Marco.VENCE_HOJE ? "Hoje é o dia: pague sua " + nome(f) + " sem acréscimo"
                    : "Lembrete amigo: sua " + nome(f) + " vence em " + dias(-marco.diasDepoisDoPrazo());
            return new Texto(assunto, ola
                    + (marco == Marco.VENCE_HOJE ? "Boa notícia: hoje ainda dá para pagar" : "Passando para adiantar uma boa notícia: ainda dá tempo de pagar")
                    + " a sua " + nome(f) + " sem nenhum acréscimo. Ela " + quando + ", no valor de " + dinheiro(f.valor()) + ".\n\n"
                    + "Pagando até o vencimento, você garante esse valor e o seu acesso à academia continua liberado, para"
                    + " você seguir treinando sem nenhuma preocupação.\n\n"
                    + "Só para você saber: se o pagamento atrasar, o valor aumenta (" + custoDoAtraso(r) + ") e, depois de "
                    + dias(r.diasParaBloquear()) + " de atraso, o acesso à academia é bloqueado até o pagamento." + fim);
        }
        long faltam = r.diasParaBloquear() - f.diasDeAtraso();
        return new Texto("Sua " + nome(f) + " está atrasada há " + dias(f.diasDeAtraso()), ola
                + "A sua " + nome(f) + ", que venceu em " + data(f.prazo()) + ", está atrasada há " + dias(f.diasDeAtraso())
                + ". O valor para pagar hoje é " + dinheiro(f.devidoHoje()) + " (era " + dinheiro(f.valor()) + ").\n\n"
                + "O atraso aumenta o valor da fatura (" + custoDoAtraso(r) + ") e bloqueia o acesso à academia: "
                + (faltam > 0 ? "o bloqueio começa com " + dias(r.diasParaBloquear()) + " de atraso, daqui a " + dias(faltam) + "."
                        : "o acesso já está bloqueado até o pagamento.")
                + "\n\nSe você já pagou por boleto nos últimos dias, pode desconsiderar este aviso: o banco leva até 3 dias"
                + " úteis para confirmar o pagamento." + fim);
    }

    /** "mensalidade 3", "multa por sair antes", "plano à vista". */
    static String nome(Fatura f) {
        return switch (f.tipo()) {
            case "MENSALIDADE" -> "mensalidade " + f.parcela();
            case "MULTA" -> "multa por sair antes";
            default -> "fatura do plano à vista";
        };
    }

    /** "acréscimo de 2% e juros de 1% ao mês"; sem acréscimo ou sem juros, só o que houver. */
    static String custoDoAtraso(Regras r) {
        String acrescimo = r.acrescimo().signum() == 0 ? null
                : "acréscimo de " + (r.acrescimoPercentual() ? numero(r.acrescimo()) + "%" : dinheiro(r.acrescimo()));
        String juros = r.jurosAoMes().signum() == 0 ? null : "juros de " + numero(r.jurosAoMes()) + "% ao mês";
        if (acrescimo == null && juros == null) {
            return "conforme as regras da academia";
        }
        return acrescimo == null ? juros : juros == null ? acrescimo : acrescimo + " e " + juros;
    }

    static String primeiroNome(String nome) {
        String limpo = nome == null ? "" : nome.strip();
        return limpo.isEmpty() ? "tudo bem" : limpo.split("\\s+")[0];
    }

    static String dias(long dias) {
        return dias == 1 ? "1 dia" : dias + " dias";
    }

    static String data(LocalDate data) {
        return DATA.format(data);
    }

    /** "R$ 1.234,50", com espaço comum (e não o espaço sem quebra do Java), que todo leitor de e-mail mostra igual. */
    static String dinheiro(BigDecimal valor) {
        return NumberFormat.getCurrencyInstance(BRASIL).format(valor).replace(' ', ' ');
    }

    private static String numero(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
