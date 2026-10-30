package br.com.academiagestao.matriculas;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criar, trancar, registrar a volta, cancelar e trocar de plano entram na trilha, com quem
 * fez e o que mudou (ADR 0031). Uma operação recusada não entra.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TrilhaDasMatriculasTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    private final UUID recepcao = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID aluno = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID outroPlano = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        relogio.parar();
        for (UUID usuario : List.of(recepcao, admin)) {
            jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'U', ?, 'x', ?)",
                    usuario, usuario + "@teste.local", usuario.equals(admin) ? "ADMIN" : "RECEPCAO");
        }
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 129.90, 1)", plano,
                "Mensal " + plano);
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 99.90, 3)", outroPlano,
                "Trimestral " + outroPlano);
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'MATRICULA' AND entidade_id IN "
                + "(SELECT id FROM matriculas WHERE aluno_id = ?)", aluno);
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'COBRANCA' AND entidade_id IN "
                + "(SELECT id FROM cobrancas WHERE aluno_id = ?)", aluno);
        limpeza.apagar("alunos", aluno);
        limpeza.apagarOnde("planos", "id IN (?, ?)", plano, outroPlano);
        for (UUID usuario : List.of(recepcao, admin)) {
            limpeza.apagar("usuarios", usuario);
        }
    }

    private static RequestPostProcessor como(String perfil, UUID id) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private ResultActions enviar(String caminho, String corpo) throws Exception {
        return mvc.perform(post(caminho)
                .with(como("RECEPCAO", recepcao)).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private UUID matricular(UUID planoId) throws Exception {
        String corpo = enviar("/api/matriculas", "{\"alunoId\": \"%s\", \"planoId\": \"%s\"}".formatted(aluno, planoId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(corpo, "$.id"));
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), Matricula.FUSO);
    }

    /** As linhas da trilha da matrícula, da primeira à última. */
    private List<Map<String, Object>> trilha(UUID matricula) {
        return jdbc.queryForList("SELECT autor, acao, alteracoes::text AS alteracoes FROM auditoria "
                + "WHERE entidade = 'MATRICULA' AND entidade_id = ? ORDER BY id", matricula);
    }

    @Test
    void criarTrancarVoltaECancelarEntramNaTrilha() throws Exception {
        UUID id = matricular(plano);
        LocalDate fim = hoje().plusMonths(1).minusDays(1);

        enviar("/api/matriculas/" + id + "/trancar", "{\"inicio\": \"%s\", \"voltaPrevista\": \"%s\", \"motivo\": \"Viagem\"}"
                .formatted(hoje().plusDays(5), hoje().plusDays(20))).andExpect(status().isOk());
        enviar("/api/matriculas/" + id + "/registrar-volta", "").andExpect(status().isOk());
        enviar("/api/matriculas/" + id + "/cancelar", "{\"motivo\": \"Mudou de cidade\"}").andExpect(status().isOk());
        // Recusada (já cancelada): não entra.
        enviar("/api/matriculas/" + id + "/cancelar", "{\"motivo\": \"De novo\"}").andExpect(status().isConflict());

        List<Map<String, Object>> linhas = trilha(id);
        assertThat(linhas).extracting(l -> l.get("acao"))
                .containsExactly("CRIADA", "TRANCADA", "VOLTA_REGISTRADA", "CANCELADA");
        assertThat(linhas).extracting(l -> l.get("autor")).containsOnly(recepcao.toString());

        String criada = (String) linhas.get(0).get("alteracoes");
        assertThat(criada).contains("\"alunoId\": {\"antes\": null, \"depois\": \"" + aluno + "\"}")
                .contains("\"plano\": {\"antes\": null, \"depois\": \"Mensal " + plano + "\"}")
                .contains("\"valorMensal\": {\"antes\": null, \"depois\": 129.90}")
                .contains("\"fim\": {\"antes\": null, \"depois\": \"" + fim + "\"}")
                .doesNotContain("canceladaEm");

        String trancada = (String) linhas.get(1).get("alteracoes");
        assertThat(trancada).contains("\"fim\": {\"antes\": \"" + fim + "\", \"depois\": \"" + fim.plusDays(15) + "\"}")
                .contains("\"voltaPrevista\": \"" + hoje().plusDays(20) + "\"")
                .doesNotContain("\"plano\"")
                // O motivo do trancamento pode ter dado de saúde: fica fora da trilha (ADR 0046, item 9).
                .doesNotContain("Viagem").doesNotContain("\"motivo\"");

        String volta = (String) linhas.get(2).get("alteracoes");
        assertThat(volta).contains("\"fim\": {\"antes\": \"" + fim.plusDays(15) + "\", \"depois\": \"" + fim + "\"}")
                .contains("\"volta\": \"");

        String cancelada = (String) linhas.get(3).get("alteracoes");
        assertThat(cancelada).contains("\"motivoCancelamento\": {\"antes\": null, \"depois\": \"Mudou de cidade\"}")
                .contains("canceladaEm");

        // O ADMIN consulta pela API da trilha.
        mvc.perform(get("/api/auditoria").param("entidade", "MATRICULA").param("entidadeId", id.toString())
                        .with(como("ADMIN", admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteudo.length()").value(4))
                .andExpect(jsonPath("$.conteudo[0].acao").value("CANCELADA"));
    }

    /** A troca encerra a atual (TROCADA) e cria a nova (CRIADA), que aponta para a atual. */
    @Test
    void trocaDePlanoEntraNasDuas() throws Exception {
        UUID atual = matricular(plano);
        String corpo = enviar("/api/matriculas/" + atual + "/trocar-plano", "{\"planoId\": \"%s\"}".formatted(outroPlano))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID nova = UUID.fromString(JsonPath.read(corpo, "$.id"));

        List<Map<String, Object>> daAtual = trilha(atual);
        assertThat(daAtual).extracting(l -> l.get("acao")).containsExactly("CRIADA", "TROCADA");
        assertThat((String) daAtual.get(1).get("alteracoes"))
                // O fim não muda: a matrícula se encerra pelo dia da troca.
                .isEqualTo("{\"trocadaEm\": {\"antes\": null, \"depois\": \"" + hoje() + "\"}}");

        List<Map<String, Object>> daNova = trilha(nova);
        assertThat(daNova).extracting(l -> l.get("acao")).containsExactly("CRIADA");
        assertThat(daNova.get(0).get("autor")).isEqualTo(recepcao.toString());
        assertThat((String) daNova.get(0).get("alteracoes"))
                .contains("\"trocaDe\": {\"antes\": null, \"depois\": \"" + atual + "\"}")
                .contains("\"plano\": {\"antes\": null, \"depois\": \"Trimestral " + outroPlano + "\"}");
    }

    /**
     * Pendências baixas, item 18: o que a matrícula faz nas cobranças (reprogramar no
     * trancamento, cancelar as em aberto no cancelamento) também entra, com o mesmo autor.
     */
    @Test
    void mudancasAutomaticasNasCobrancasEntramNaTrilha() throws Exception {
        UUID id = matricular(outroPlano);
        enviar("/api/matriculas/" + id + "/trancar", "{\"inicio\": \"%s\", \"voltaPrevista\": \"%s\", \"motivo\": \"Viagem\"}"
                .formatted(hoje().plusDays(5), hoje().plusDays(20))).andExpect(status().isOk());

        List<Map<String, Object>> reprogramadas = trilhaDasCobrancas(id);
        // As 2 parcelas depois do início do trancamento andam 15 dias; a de hoje, não.
        assertThat(reprogramadas).extracting(l -> l.get("acao")).containsExactly("REPROGRAMADA", "REPROGRAMADA");
        assertThat(reprogramadas).extracting(l -> l.get("autor")).containsOnly(recepcao.toString());
        LocalDate segunda = hoje().plusMonths(1);
        assertThat(reprogramadas).extracting(l -> (String) l.get("alteracoes")).contains(
                "{\"vencimento\": {\"antes\": \"" + segunda + "\", \"depois\": \"" + segunda.plusDays(15) + "\"}}");

        enviar("/api/matriculas/" + id + "/cancelar", "{\"motivo\": \"Mudou de cidade\"}").andExpect(status().isOk());

        List<Map<String, Object>> canceladas = trilhaDasCobrancas(id).stream()
                .filter(l -> "CANCELADA".equals(l.get("acao"))).toList();
        assertThat(canceladas).hasSize(jdbc.queryForObject(
                "SELECT count(*) FROM cobrancas WHERE matricula_id = ? AND status = 'CANCELADA'", Integer.class, id))
                .isNotEmpty();
        assertThat(canceladas).extracting(l -> l.get("autor")).containsOnly(recepcao.toString());
        assertThat(canceladas).extracting(l -> (String) l.get("alteracoes")).allSatisfy(alteracoes -> assertThat(alteracoes)
                .contains("\"status\": {\"antes\": \"PENDENTE\", \"depois\": \"CANCELADA\"}")
                .contains("\"motivo\": {\"antes\": null, \"depois\": \"Matrícula encerrada"));
    }

    private List<Map<String, Object>> trilhaDasCobrancas(UUID matricula) {
        return jdbc.queryForList("SELECT autor, acao, alteracoes::text AS alteracoes FROM auditoria "
                + "WHERE entidade = 'COBRANCA' AND entidade_id IN (SELECT id FROM cobrancas WHERE matricula_id = ?) "
                + "ORDER BY id", matricula);
    }

    /** Recusada antes de gravar (aluno inativo): nem matrícula nem trilha. */
    @Test
    void recusadaNaoEntra() throws Exception {
        jdbc.update("UPDATE alunos SET ativo = false WHERE id = ?", aluno);
        enviar("/api/matriculas", "{\"alunoId\": \"%s\", \"planoId\": \"%s\"}".formatted(aluno, plano))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria WHERE entidade = 'MATRICULA' AND "
                + "alteracoes::text LIKE ?", Integer.class, "%" + aluno + "%")).isZero();
    }
}
