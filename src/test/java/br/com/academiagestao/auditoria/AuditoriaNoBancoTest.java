package br.com.academiagestao.auditoria;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static br.com.academiagestao.compartilhado.Auditoria.campos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
