package br.com.academiagestao.compartilhado;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

import java.util.Properties;

/** Escolhe o correio: SMTP, se o {@code .env} tem um servidor; desligado, se não (ADR 0038). */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
class CorreioConfig {

    private static final Logger log = LoggerFactory.getLogger(CorreioConfig.class);

    @Bean
    Correio correio(EmailProperties propriedades) {
        if (!StringUtils.hasText(propriedades.host())) {
            log.info("E-mail desligado: sem servidor de e-mail no .env, nada que dependa de e-mail é exigido.");
            return new Desligado();
        }
        if (!StringUtils.hasText(propriedades.remetente())) {
            throw new IllegalStateException("academia.email.remetente é obrigatório com um servidor de e-mail");
        }
        return new Smtp(enviador(propriedades), propriedades.remetente());
    }

    static JavaMailSender enviador(EmailProperties p) {
        JavaMailSenderImpl enviador = new JavaMailSenderImpl();
        enviador.setHost(p.host());
        enviador.setPort(p.porta());
        enviador.setDefaultEncoding("UTF-8");
        Properties extras = enviador.getJavaMailProperties();
        String espera = String.valueOf(p.espera().toMillis());
        extras.put("mail.smtp.connectiontimeout", espera);
        extras.put("mail.smtp.timeout", espera);
        extras.put("mail.smtp.writetimeout", espera);
        extras.put("mail.smtp.starttls.enable", String.valueOf(p.tls()));
        extras.put("mail.smtp.starttls.required", String.valueOf(p.tls()));
        if (StringUtils.hasText(p.usuario())) {
            enviador.setUsername(p.usuario());
            enviador.setPassword(p.senha());
            extras.put("mail.smtp.auth", "true");
        }
        return enviador;
    }

    /** Sem servidor: não envia, e quem pergunta sabe que está desligado. */
    static final class Desligado implements Correio {

        @Override
        public boolean ligado() {
            return false;
        }

        @Override
        public void enviar(Mensagem mensagem) {
            throw new IllegalStateException("e-mail desligado: quem chama deveria ter conferido ligado()");
        }
    }

    static final class Smtp implements Correio {

        private final JavaMailSender enviador;
        private final String remetente;

        Smtp(JavaMailSender enviador, String remetente) {
            this.enviador = enviador;
            this.remetente = remetente;
        }

        @Override
        public boolean ligado() {
            return true;
        }

        @Override
        public void enviar(Mensagem mensagem) {
            SimpleMailMessage email = new SimpleMailMessage();
            email.setFrom(remetente);
            email.setTo(mensagem.para());
            // Uma quebra de linha no assunto não abre outro cabeçalho: o Jakarta Mail a neutraliza (o CorreioTest confere
            // com um servidor de verdade, diagnóstico do ADR 0047).
            email.setSubject(mensagem.assunto());
            email.setText(mensagem.texto());
            try {
                enviador.send(email);
            } catch (MailException e) {
                // Só o tipo do erro: a mensagem do servidor pode trazer o endereço (dado pessoal).
                throw new CorreioIndisponivelException("o servidor de e-mail recusou ou não respondeu ("
                        + e.getMostSpecificCause().getClass().getSimpleName() + ")", e);
            }
        }
    }
}
