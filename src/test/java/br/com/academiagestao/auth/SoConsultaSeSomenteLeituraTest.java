package br.com.academiagestao.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O login só de leitura (aluno inativo, ADR 0029) passa só em consulta, em qualquer rota.
 * Hoje nenhuma rota de aluno altera dados além da troca de senha (liberada antes desta
 * regra): a regra protege as que vierem, como "registrar o treino feito".
 */
class SoConsultaSeSomenteLeituraTest {

    @ParameterizedTest(name = "{0} com {1} -> {2}")
    @CsvSource({
            "GET,    ROLE_ALUNO SOMENTE_LEITURA, true",
            "POST,   ROLE_ALUNO SOMENTE_LEITURA, false",
            "PUT,    ROLE_ALUNO SOMENTE_LEITURA, false",
            "PATCH,  ROLE_ALUNO SOMENTE_LEITURA, false",
            "DELETE, ROLE_ALUNO SOMENTE_LEITURA, false",
            "POST,   ROLE_ALUNO,                 true",
            "DELETE, ROLE_ADMIN,                 true",
    })
    void soConsultaComSomenteLeitura(String metodo, String autoridades, boolean passa) {
        var autenticacao = new TestingAuthenticationToken("ana", null, autoridades.split(" "));
        var pedido = new MockHttpServletRequest(metodo, "/api/qualquer");

        var decisao = SegurancaConfig.SO_CONSULTA_SE_SOMENTE_LEITURA.authorize(() -> autenticacao,
                new RequestAuthorizationContext(pedido));

        assertThat(decisao.isGranted()).isEqualTo(passa);
    }

    /** A claim do token vira a autoridade, junto com o perfil (e não no lugar dele). */
    @Test
    void claimDoTokenViraAutoridadeJuntoComOPerfil() {
        var conversor = new SegurancaConfig().jwtAuthenticationConverter();
        Jwt soLeitura = Jwt.withTokenValue("t").header("alg", "HS256").subject("ana")
                .claim(ClaimsDoToken.PERFIL, "ALUNO").claim(ClaimsDoToken.SOMENTE_LEITURA, true).build();
        Jwt normal = Jwt.withTokenValue("t").header("alg", "HS256").subject("ana")
                .claim(ClaimsDoToken.PERFIL, "ALUNO").build();

        assertThat(conversor.convert(soLeitura).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ALUNO", SegurancaConfig.SOMENTE_LEITURA);
        assertThat(conversor.convert(normal).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ALUNO").doesNotContain(SegurancaConfig.SOMENTE_LEITURA);
    }
}
