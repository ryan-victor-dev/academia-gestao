package br.com.academiagestao.avisos;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    /** Um evento ou comunicado publicado com "avisar" (ADR 0047, decisão 8), como estava ao publicar. */
    record DaAcademia(String tipo, String titulo, String texto, Instant eventoInicio, Instant eventoFim, String eventoLocal) {

        boolean evento() {
            return "EVENTO".equals(tipo);
        }
    }

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_E_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");
    private static final DateTimeFormatter DIA_E_HORA_CURTOS = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final int TITULO_NO_SMS = 80;

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

    /**
     * A versão curta, para SMS (até uns 200 caracteres, duas mensagens no máximo): o mesmo conteúdo, sem a despedida.
     * Começa por "Academia:" (o SMS chega de um número que a pessoa não conhece).
     */
    static String sms(Marco marco, Fatura f, Regras r) {
        String nome = primeiroNome(f.nomeDoAluno());
        if (!marco.atrasada()) {
            String quando = marco == Marco.VENCE_HOJE ? "vence hoje" : "vence em " + dias(-marco.diasDepoisDoPrazo())
                    + " (" + DATA.format(f.prazo()).substring(0, 5) + ")";
            return "Academia: oi, " + nome + "! Sua " + nome(f) + " de " + dinheiro(f.valor()) + " " + quando
                    + ". Pague até lá sem acréscimo e com o acesso liberado. O atraso aumenta o valor e bloqueia o acesso"
                    + " após " + dias(r.diasParaBloquear()) + ".";
        }
        long faltam = r.diasParaBloquear() - f.diasDeAtraso();
        return "Academia: " + nome + ", sua " + nome(f) + " está atrasada há " + dias(f.diasDeAtraso()) + ". Hoje: "
                + dinheiro(f.devidoHoje()) + ". O atraso aumenta o valor e bloqueia o acesso"
                + (faltam > 0 ? " em " + dias(faltam) : " (já bloqueado)") + ". Pagou por boleto há pouco? Desconsidere.";
    }

    /** O e-mail do evento ou comunicado: o conteúdo inteiro e, no fim, como deixar de receber. */
    static Texto daAcademia(DaAcademia a, String nomeDaPessoa) {
        String quando = !a.evento() ? "" : "\n" + quandoEOnde(a) + "\n";
        return new Texto((a.evento() ? "Evento da academia: " : "Comunicado da academia: ") + a.titulo(),
                "Olá, " + primeiroNome(nomeDaPessoa) + "!\n\n" + (a.evento() ? "Tem evento novo na academia:" : "Comunicado da academia:")
                        + "\n\n" + a.titulo() + "\n" + quando + "\n" + a.texto() + "\n\nVeja também na página inicial do app."
                        + "\n\n--\nPara não receber mais avisos de eventos e comunicados, desligue em Meus dados, no app."
                        + " Os lembretes de fatura continuam.");
    }

    /** O SMS do evento ou comunicado: só o título (até 80 caracteres), quando e onde, e para ver no app. */
    static String daAcademiaSms(DaAcademia a) {
        String titulo = a.titulo().length() > TITULO_NO_SMS ? a.titulo().substring(0, TITULO_NO_SMS - 1) + "…" : a.titulo();
        String quando = !a.evento() ? "" : " - " + DIA_E_HORA_CURTOS.format(a.eventoInicio().atZone(FUSO))
                + (a.eventoLocal() == null ? "" : ", " + a.eventoLocal());
        return "Academia: " + (a.evento() ? "evento: " : "comunicado: ") + titulo + quando + ". Veja no app.";
    }

    /** "07/11/2030 às 09:00 até 11:00 · Sala 2" (o fim em outro dia leva a data). */
    static String quandoEOnde(DaAcademia a) {
        var inicio = a.eventoInicio().atZone(FUSO);
        String fim = "";
        if (a.eventoFim() != null) {
            var ate = a.eventoFim().atZone(FUSO);
            fim = " até " + (ate.toLocalDate().equals(inicio.toLocalDate()) ? DateTimeFormatter.ofPattern("HH:mm").format(ate)
                    : DATA_E_HORA.format(ate));
        }
        return DATA_E_HORA.format(inicio) + fim + (a.eventoLocal() == null ? "" : " · " + a.eventoLocal());
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
