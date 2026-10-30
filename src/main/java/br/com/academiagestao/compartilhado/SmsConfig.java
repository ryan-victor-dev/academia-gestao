package br.com.academiagestao.compartilhado;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Escolhe o SMS (ADR 0047): {@code desligado} (o padrão) ou {@code simulado}. A empresa de verdade ainda não foi
 * escolhida pelo dono; quando for, entra aqui como mais uma opção. O simulado guarda as mensagens numa tabela, em vez
 * de mandar, e não sobe num servidor de verdade (com DOMINIO no .env), como o pagamento simulado (ADR 0041): lá, ele
 * faria o sistema achar que avisou quem nunca recebeu nada.
 */
@Configuration
class SmsConfig {

    private static final Logger log = LoggerFactory.getLogger(SmsConfig.class);

    @Bean
    MensageiroSms mensageiroSms(@Value("${academia.sms.provedor:desligado}") String provedor,
                                @Value("${academia.dominio:}") String dominio, JdbcTemplate jdbc, Clock relogio) {
        return switch (provedor) {
            case "desligado" -> {
                log.info("SMS desligado: os avisos saem só por e-mail.");
                yield new Desligado();
            }
            case "simulado" -> {
                if (dominio != null && !dominio.isBlank() && !dominio.equals("localhost")) {
                    throw new IllegalStateException("SMS simulado recusado: o .env tem DOMINIO=" + dominio
                            + ", então isto parece um servidor de verdade. Use SMS_PROVEDOR=desligado ou uma empresa.");
                }
                log.warn("SMS SIMULADO: as mensagens ficam na tabela sms_simulados e não saem para ninguém.");
                yield new Simulado(jdbc, relogio);
            }
            default -> throw new IllegalStateException("academia.sms.provedor desconhecido: " + provedor
                    + " (use desligado ou simulado)");
        };
    }

    static final class Desligado implements MensageiroSms {
        @Override
        public boolean ligado() {
            return false;
        }

        @Override
        public void enviar(String celular, String texto) {
            throw new IllegalStateException("SMS desligado: quem chama deveria ter conferido ligado()");
        }
    }

    /** Só desenvolvimento e testes: guarda em vez de mandar. */
    static final class Simulado implements MensageiroSms {
        private final JdbcTemplate jdbc;
        private final Clock relogio;

        Simulado(JdbcTemplate jdbc, Clock relogio) {
            this.jdbc = jdbc;
            this.relogio = relogio;
        }

        @Override
        public boolean ligado() {
            return true;
        }

        @Override
        public void enviar(String celular, String texto) {
            if (!MensageiroSms.ehCelular(celular)) {
                throw new SmsIndisponivelException("número que não é celular: a empresa recusaria", null);
            }
            jdbc.update("INSERT INTO sms_simulados (id, celular, texto, enviado_em) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), celular, texto, Timestamp.from(relogio.instant()));
        }
    }
}
