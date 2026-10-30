package br.com.academiagestao.compartilhado;

/**
 * Envio de SMS (ADR 0047, decisão 9): os avisos de fatura e os da academia. Construído atrás desta porta, como o
 * pagamento online (ADR 0034): desligado por padrão, simulado para desenvolvimento e testes, e a empresa (Zenvia,
 * Twilio, AWS SNS...) escolhida antes de ir ao ar, numa classe nova que implementa a porta. A chamada vai para fora
 * (rede): nunca dentro de uma transação do banco.
 */
public interface MensageiroSms {

    /** Se há uma empresa de SMS configurada (ou o simulado). */
    boolean ligado();

    /**
     * @param celular só os dígitos, com DDD (11 dígitos: SMS só vai para celular)
     * @throws SmsIndisponivelException a empresa fora do ar ou recusando
     */
    void enviar(String celular, String texto);

    /** Celular brasileiro com DDD: 11 dígitos, o terceiro é 9. Telefone fixo não recebe SMS. */
    static boolean ehCelular(String telefone) {
        return telefone != null && telefone.matches("[1-9]{2}9\\d{8}");
    }
}
