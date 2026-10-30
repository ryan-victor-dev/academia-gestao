package br.com.academiagestao.noticias;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Endereço assinado de um anexo (ADR 0047). A foto e o vídeo entram na página por {@code <img>} e {@code <video>},
 * que não mandam o token: quem tem login recebe, junto da publicação, um endereço que vale por um tempo, assinado
 * com uma chave que só a aplicação conhece (derivada do segredo do JWT, sem configuração nova). Sem a assinatura
 * certa, ou depois do prazo, o anexo não sai. O prazo cai sempre na virada de uma hora (entre 1 e 2 horas): na
 * mesma hora, o endereço é o mesmo, e o navegador aproveita o que já baixou.
 */
@Component
class AssinaturaDosAnexos {

    static final Duration VALIDADE_MINIMA = Duration.ofHours(1);

    private final byte[] chave;
    private final Clock relogio;

    AssinaturaDosAnexos(@Value("${academia.jwt.segredo}") String segredoDoJwt, Clock relogio) {
        this.chave = hmac(Base64.getDecoder().decode(segredoDoJwt), "anexos-das-publicacoes-v1");
        this.relogio = relogio;
    }

    record Endereco(String caminho, Instant expira) {
    }

    Endereco assinar(UUID anexoId) {
        Instant expira = relogio.instant().plus(VALIDADE_MINIMA).truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
        return new Endereco("/api/anexos/%s?expira=%d&assinatura=%s".formatted(anexoId, expira.getEpochSecond(),
                assinatura(anexoId, expira.getEpochSecond())), expira);
    }

    /** Quanto falta para o prazo (zero, se já passou): o tempo de cache do navegador. */
    Duration restante(long expira) {
        Duration restante = Duration.between(relogio.instant(), Instant.ofEpochSecond(expira));
        return restante.isNegative() ? Duration.ZERO : restante;
    }

    /** Comparação em tempo constante: quem tenta adivinhar não aprende nada pelo tempo da resposta. */
    boolean confere(UUID anexoId, long expira, String assinatura) {
        if (assinatura == null || Instant.ofEpochSecond(expira).isBefore(relogio.instant())) {
            return false;
        }
        return MessageDigest.isEqual(assinatura(anexoId, expira).getBytes(StandardCharsets.US_ASCII),
                assinatura.getBytes(StandardCharsets.US_ASCII));
    }

    private String assinatura(UUID anexoId, long expira) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(chave, anexoId + ":" + expira));
    }

    private static byte[] hmac(byte[] chave, String mensagem) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(chave, "HmacSHA256"));
            return mac.doFinal(mensagem.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponível", e);
        }
    }
}
