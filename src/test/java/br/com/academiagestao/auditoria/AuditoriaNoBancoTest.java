package br.com.academiagestao.auditoria;

import br.com.academiagestao.compartilhado.Auditoria;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.campos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/** Só o que mudou entra na trilha. */
class AuditoriaNoBancoTest {

    @Test
    void guardaSoOsCamposQueMudaramComAntesEDepois() {
        var alteracoes = AuditoriaNoBanco.alteracoes(
                campos("nome", "Mensal", "valor", 129.90, "ativo", true),
                campos("nome", "Mensal", "valor", 149.90, "ativo", true));

        assertThat(alteracoes).containsOnlyKeys("valor");
        assertThat(alteracoes.get("valor")).containsEntry("antes", 129.90).containsEntry("depois", 149.90);
    }

    @Test
    void naCriacaoTodosOsCamposVemDoNada() {
        var alteracoes = AuditoriaNoBanco.alteracoes(Map.of(), campos("nome", "Mensal", "percentual", null));

        // Campo que nasce vazio não mudou: não aparece.
        assertThat(alteracoes).containsOnlyKeys("nome");
        assertThat(alteracoes.get("nome")).containsEntry("antes", null).containsEntry("depois", "Mensal");
    }

    @Test
    void campoQueFicaVazioAparece() {
        var alteracoes = AuditoriaNoBanco.alteracoes(campos("email", "ana@x.com"), campos("email", null));

        assertThat(alteracoes.get("email")).containsEntry("antes", "ana@x.com").containsEntry("depois", null);
    }

    @Test
    void semMudancaFicaVazio() {
        assertThat(AuditoriaNoBanco.alteracoes(campos("ativo", true), campos("ativo", true))).isEmpty();
    }

    @Test
    void camposRecebePares() {
        assertThatThrownBy(() -> campos("nome")).isInstanceOf(IllegalArgumentException.class);
    }

    /** O autor é o "sub" do token; sem token (o sistema) ou com outro tipo de login, nulo. */
    @Test
    void autorEhOSubDoToken() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditoriaNoBanco trilha = new AuditoriaNoBanco(jdbc, JsonMapper.builder().build());
        UUID id = UUID.randomUUID();
        try {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("usuario-1").build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
            trilha.registrar(Auditoria.Entidade.PLANO, id, "CRIADO", Map.of(), Map.of());
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("outro", null));
            trilha.registrar(Auditoria.Entidade.PLANO, id, "EDITADO", Map.of(), Map.of());
            SecurityContextHolder.clearContext();
            trilha.registrar(Auditoria.Entidade.PLANO, id, "INATIVADO", Map.of(), Map.of());
        } finally {
            SecurityContextHolder.clearContext();
        }

        InOrder ordem = inOrder(jdbc);
        ordem.verify(jdbc).update(anyString(), eq("usuario-1"), eq("PLANO"), eq(id), eq("CRIADO"), eq("{}"));
        ordem.verify(jdbc).update(anyString(), isNull(), eq("PLANO"), eq(id), eq("EDITADO"), eq("{}"));
        ordem.verify(jdbc).update(anyString(), isNull(), eq("PLANO"), eq(id), eq("INATIVADO"), eq("{}"));
    }
}
