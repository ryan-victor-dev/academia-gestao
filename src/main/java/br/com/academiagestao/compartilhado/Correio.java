package br.com.academiagestao.compartilhado;

/**
 * Envio de e-mail (ADR 0038): os códigos de confirmação do e-mail e de recuperação da senha.
 * Desligado (sem servidor de e-mail no {@code .env}), o sistema não exige nem oferece nada que
 * dependa de e-mail. A chamada vai para fora (rede): nunca dentro de uma transação do banco.
 */
public interface Correio {

    /** Se há um servidor de e-mail configurado. */
    boolean ligado();

    /** @throws CorreioIndisponivelException servidor de e-mail fora do ar ou recusando */
    void enviar(Mensagem mensagem);

    /** Texto simples: sem HTML, nada para o leitor de e-mail interpretar. */
    record Mensagem(String para, String assunto, String texto) {
    }
}
