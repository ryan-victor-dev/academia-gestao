package br.com.academiagestao.compartilhado;

import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** A escolha do SMS e o que é celular (ADR 0047). */
class SmsConfigTest {

    private final SmsConfig config = new SmsConfig();

    @Test
    void desligadoPorPadraoNaoEnvia() {
        MensageiroSms sms = config.mensageiroSms("desligado", "", null, Clock.systemUTC());
        assertThat(sms.ligado()).isFalse();
        assertThatThrownBy(() -> sms.enviar("11987654321", "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void simuladoSoSemDominioDeVerdade() {
        assertThat(config.mensageiroSms("simulado", "", null, Clock.systemUTC()).ligado()).isTrue();
        assertThat(config.mensageiroSms("simulado", "localhost", null, Clock.systemUTC()).ligado()).isTrue();
        assertThat(config.mensageiroSms("simulado", null, null, Clock.systemUTC()).ligado()).isTrue();
        assertThatThrownBy(() -> config.mensageiroSms("simulado", "academia.com.br", null, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DOMINIO=academia.com.br");
    }

    @Test
    void oSimuladoGuardaEmVezDeMandarESoParaCelular() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant agora = Instant.parse("2026-11-10T12:00:00Z");
        MensageiroSms sms = config.mensageiroSms("simulado", "", jdbc, Clock.fixed(agora, ZoneOffset.UTC));

        sms.enviar("11987654321", "Oi");
        verify(jdbc).update(anyString(), any(UUID.class), eq("11987654321"), eq("Oi"), eq(Timestamp.from(agora)));

        JdbcTemplate outro = mock(JdbcTemplate.class);
        MensageiroSms comOutro = config.mensageiroSms("simulado", "", outro, Clock.systemUTC());
        assertThatThrownBy(() -> comOutro.enviar("1133334444", "Oi")).isInstanceOf(SmsIndisponivelException.class);
        verifyNoInteractions(outro);
    }

    @Test
    void provedorDesconhecidoNaoSobe() {
        assertThatThrownBy(() -> config.mensageiroSms("twilio", "", null, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("twilio");
    }

    @Test
    void soCelularComDdd() {
        assertThat(MensageiroSms.ehCelular("11987654321")).isTrue();
        assertThat(MensageiroSms.ehCelular("21912345678")).isTrue();
        assertThat(MensageiroSms.ehCelular("1133334444")).as("fixo").isFalse();
        assertThat(MensageiroSms.ehCelular("11887654321")).as("sem o 9").isFalse();
        assertThat(MensageiroSms.ehCelular("01987654321")).as("DDD com 0").isFalse();
        assertThat(MensageiroSms.ehCelular("10987654321")).as("DDD com 0 no fim").isFalse();
        assertThat(MensageiroSms.ehCelular("1198765432")).isFalse();
        assertThat(MensageiroSms.ehCelular("119876543210")).isFalse();
        assertThat(MensageiroSms.ehCelular("(11)98765-4321")).isFalse();
        assertThat(MensageiroSms.ehCelular(null)).isFalse();
    }
}
