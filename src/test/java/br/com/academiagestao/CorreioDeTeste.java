package br.com.academiagestao;

import br.com.academiagestao.compartilhado.Correio;
import br.com.academiagestao.compartilhado.CorreioIndisponivelException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Um correio ligado que guarda as mensagens em vez de mandar (ADR 0038). Para o teste que
 * precisa do e-mail ligado: {@code @Import(CorreioDeTeste.Config.class)}. Pode fingir que o
 * servidor caiu ({@link #falhar}).
 */
public class CorreioDeTeste implements Correio {

    private static final Pattern CODIGO = Pattern.compile("\\b(\\d{6})\\b");

    private final List<Mensagem> mensagens = new CopyOnWriteArrayList<>();
    private volatile boolean falhar;
    private volatile boolean desligado;

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        CorreioDeTeste correioDeTeste() {
            return new CorreioDeTeste();
        }
    }

    @Override
    public boolean ligado() {
        return !desligado;
    }

    @Override
    public void enviar(Mensagem mensagem) {
        if (falhar) {
            throw new CorreioIndisponivelException("servidor de teste fora do ar", null);
        }
        mensagens.add(mensagem);
    }

    public void falhar(boolean falhar) {
        this.falhar = falhar;
    }

    /** Finge que não há servidor de e-mail configurado (ligado() falso). */
    public void desligar(boolean desligado) {
        this.desligado = desligado;
    }

    public List<Mensagem> para(String email) {
        return mensagens.stream().filter(m -> m.para().equals(email)).toList();
    }

    /** O código de 6 dígitos da última mensagem para o e-mail, se houver. */
    public Optional<String> ultimoCodigo(String email) {
        List<Mensagem> doEmail = para(email);
        if (doEmail.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = CODIGO.matcher(doEmail.getLast().texto());
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
