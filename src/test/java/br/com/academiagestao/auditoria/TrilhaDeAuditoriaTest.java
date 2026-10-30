package br.com.academiagestao.auditoria;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Auditoria;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Revisão de 2026-10-17 (M3): mudar preço e regras de um plano, editar ou inativar alunos e
 * mexer na equipe não deixava rastro de quem fez nem do valor anterior.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TrilhaDeAuditoriaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    Auditoria auditoria;

    private final UUID adminId = UUID.randomUUID();
    private final String adminNome = "Admin da Auditoria " + adminId;
    private final List<UUID> criados = new ArrayList<>();

    @BeforeEach
    void criarAdmin() {
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, ?, ?, 'x', 'ADMIN')",
                adminId, adminNome, "auditoria-" + adminId + "@teste.local");
        criados.add(adminId);
    }

    @AfterEach
    void limpar() {
        for (UUID id : criados) {
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ? OR autor = ?", id, id.toString());
            jdbc.update("DELETE FROM usuarios WHERE id = ? OR aluno_id = ?", id, id);
            jdbc.update("DELETE FROM alunos WHERE id = ?", id);
            jdbc.update("DELETE FROM planos WHERE id = ?", id);
        }
    }

    private RequestPostProcessor comoAdmin() {
        return jwt().jwt(j -> j.subject(adminId.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private ResultActions enviar(MockHttpServletRequestBuilder requisicao, String corpo) throws Exception {
        return mvc.perform(requisicao.with(comoAdmin()).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private UUID criado(ResultActions resposta) throws Exception {
        UUID id = UUID.fromString(JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), "$.id"));
        criados.add(id);
        return id;
    }

    private ResultActions trilha(String entidade, UUID id) throws Exception {
        return mvc.perform(get("/api/auditoria").param("entidade", entidade).param("entidadeId", id.toString())
                .with(comoAdmin()));
    }

    @Test
    void reajusteDePlanoGuardaQuemFezEOValorAnterior() throws Exception {
        UUID plano = criado(enviar(post("/api/planos"), """
                {"nome": "Auditado %s", "valorMensal": 129.90, "duracaoMeses": 12,
                 "fidelidade": true, "percentualMulta": 20}""".formatted(UUID.randomUUID()))
                .andExpect(status().isCreated()));
        String nome = JsonPath.read(trilha("PLANO", plano).andReturn().getResponse().getContentAsString(),
                "$.conteudo[0].alteracoes.nome.depois");
        enviar(put("/api/planos/" + plano).header("If-Match", "\"0\""), """
                {"nome": "%s", "valorMensal": 149.90, "duracaoMeses": 12, "fidelidade": true, "percentualMulta": 10}"""
                .formatted(nome)).andExpect(status().isOk());

        trilha("PLANO", plano)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(2))
                .andExpect(jsonPath("$.conteudo[*].acao").value(contains("EDITADO", "CRIADO")))
                .andExpect(jsonPath("$.conteudo[0].autor").value(adminId.toString()))
                .andExpect(jsonPath("$.conteudo[0].autorNome").value(adminNome))
                // Só o que mudou: preço e multa.
                .andExpect(jsonPath("$.conteudo[0].alteracoes").value(aMapWithSize(2)))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.valorMensal.antes").value(129.90))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.valorMensal.depois").value(149.90))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.percentualMulta.antes").value(20.00))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.percentualMulta.depois").value(10.00))
                .andExpect(jsonPath("$.conteudo[1].alteracoes.fidelidade.depois").value(true))
                .andExpect(content().string(containsString("\"valorMensal\":{\"antes\":129.90,\"depois\":149.90}")));

        enviar(post("/api/planos/" + plano + "/inativar"), "").andExpect(status().isOk());
        trilha("PLANO", plano)
                .andExpect(jsonPath("$.conteudo[0].acao").value("INATIVADO"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.ativo.antes").value(true))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.ativo.depois").value(false));

        enviar(post("/api/planos/" + plano + "/reativar"), "").andExpect(status().isOk());
        trilha("PLANO", plano)
                .andExpect(jsonPath("$.conteudo[0].acao").value("REATIVADO"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.ativo.depois").value(true));
    }

    @Test
    void edicaoEInativacaoDeAlunoGuardamOValorAnterior() throws Exception {
        String cpf = CpfsParaTeste.aleatorio();
        String email = "ana-" + UUID.randomUUID() + "@teste.local";
        UUID aluno = criado(enviar(post("/api/alunos"), """
                {"nome": "Ana Auditada", "cpf": "%s", "email": "%s", "telefone": "31987654321"}"""
                .formatted(cpf, email)).andExpect(status().isCreated()));
        enviar(put("/api/alunos/" + aluno).header("If-Match", "\"0\""), """
                {"nome": "Ana Auditada", "cpf": "%s", "email": "%s", "telefone": "31912345678"}"""
                .formatted(cpf, email)).andExpect(status().isOk());
        enviar(post("/api/alunos/" + aluno + "/login"), "").andExpect(status().isCreated());
        UUID login = jdbc.queryForObject("SELECT id FROM usuarios WHERE aluno_id = ?", UUID.class, aluno);
        criados.add(login);
        enviar(post("/api/alunos/" + aluno + "/inativar"), "").andExpect(status().isOk());

        trilha("ALUNO", aluno)
                .andExpect(jsonPath("$.conteudo[*].acao").value(contains("INATIVADO", "EDITADO", "CRIADO")))
                .andExpect(jsonPath("$.conteudo[1].alteracoes").value(aMapWithSize(1)))
                .andExpect(jsonPath("$.conteudo[1].alteracoes.telefone.antes").value("31987654321"))
                .andExpect(jsonPath("$.conteudo[1].alteracoes.telefone.depois").value("31912345678"))
                .andExpect(jsonPath("$.conteudo[2].alteracoes.cpf.depois").value(cpf));
        // O login do aluno nasce com ele e fica só de leitura na inativação (ADR 0029), na trilha.
        trilha("USUARIO", login)
                .andExpect(jsonPath("$.conteudo[*].acao").value(contains("SOMENTE_LEITURA", "CRIADO")))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.somenteLeitura.depois").value(true))
                .andExpect(jsonPath("$.conteudo[1].alteracoes.perfil.depois").value("ALUNO"))
                .andExpect(jsonPath("$.conteudo[1].alteracoes.alunoId.depois").value(aluno.toString()))
                .andExpect(jsonPath("$.conteudo[0].autor").value(adminId.toString()));

        enviar(post("/api/alunos/" + aluno + "/reativar"), "").andExpect(status().isOk());
        trilha("ALUNO", aluno)
                .andExpect(jsonPath("$.conteudo[0].acao").value("REATIVADO"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.ativo.depois").value(true));
        trilha("USUARIO", login)
                .andExpect(jsonPath("$.conteudo[0].acao").value("LIBERADO"))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.somenteLeitura.depois").value(false));
    }

    @Test
    void equipeESenhasFicamNaTrilhaSemNenhumaSenha() throws Exception {
        UUID ana = criado(enviar(post("/api/usuarios"), """
                {"nome": "Ana", "email": "ana-%s@teste.local", "cpf": "%s", "perfil": "RECEPCAO"}"""
                .formatted(UUID.randomUUID(), CpfsParaTeste.aleatorio()))
                .andExpect(status().isCreated()));
        enviar(put("/api/usuarios/" + ana), """
                {"nome": "Ana Maria", "perfil": "INSTRUTOR"}""").andExpect(status().isOk());
        enviar(post("/api/usuarios/" + ana + "/nova-senha"), "").andExpect(status().isOk());
        enviar(post("/api/usuarios/" + ana + "/desativar"), "").andExpect(status().isOk());
        enviar(post("/api/usuarios/" + ana + "/reativar"), "").andExpect(status().isOk());

        String corpo = trilha("USUARIO", ana)
                .andExpect(jsonPath("$.conteudo[*].acao")
                        .value(contains("REATIVADO", "DESATIVADO", "SENHA_REDEFINIDA", "EDITADO", "CRIADO")))
                .andExpect(jsonPath("$.conteudo[0].alteracoes.ativo.depois").value(true))
                .andExpect(jsonPath("$.conteudo[1].alteracoes.ativo.depois").value(false))
                .andExpect(jsonPath("$.conteudo[2].alteracoes").value(anEmptyMap()))
                .andExpect(jsonPath("$.conteudo[2].autorNome").value(adminNome))
                .andExpect(jsonPath("$.conteudo[3].alteracoes.perfil.antes").value("RECEPCAO"))
                .andExpect(jsonPath("$.conteudo[3].alteracoes.perfil.depois").value("INSTRUTOR"))
                .andExpect(jsonPath("$.conteudo[3].alteracoes.nome.depois").value("Ana Maria"))
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContainIgnoringCase("senhaHash").doesNotContain("$2a$").doesNotContain("$2b$");
    }

    @Test
    void trocaDaPropriaSenhaRegistraOProprioUsuario() throws Exception {
        UUID bia = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Bia', ?, ?, 'RECEPCAO')",
                bia, "bia-" + bia + "@teste.local", passwordEncoder.encode("senha-antiga-123"));
        criados.add(bia);

        mvc.perform(post("/api/auth/trocar-senha").with(jwt().jwt(j -> j.subject(bia.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\": \"senha-antiga-123\", \"novaSenha\": \"senha-nova-de-teste-456\"}"))
                .andExpect(status().isOk());

        trilha("USUARIO", bia)
                .andExpect(jsonPath("$.conteudo[0].acao").value("SENHA_TROCADA"))
                .andExpect(jsonPath("$.conteudo[0].autor").value(bia.toString()));
    }

    @Test
    void alteracaoRecusadaNaoDeixaRegistro() throws Exception {
        UUID plano = criado(enviar(post("/api/planos"), """
                {"nome": "Recusado %s", "valorMensal": 100, "duracaoMeses": 1}""".formatted(UUID.randomUUID()))
                .andExpect(status().isCreated()));

        // Versão errada no If-Match: 412, nada muda e nada é registrado além da criação.
        enviar(put("/api/planos/" + plano).header("If-Match", "\"7\""), """
                {"nome": "Outro", "valorMensal": 1, "duracaoMeses": 1}""").andExpect(status().isPreconditionFailed());

        trilha("PLANO", plano).andExpect(jsonPath("$.conteudo[*].acao").value(contains("CRIADO")));
    }

    /** Se o registro falhar, a alteração também não fica: as duas vão na mesma transação. */
    @Test
    void semRegistroNaoHaAlteracao() throws Exception {
        UUID plano = criado(enviar(post("/api/planos"), """
                {"nome": "Atomico %s", "valorMensal": 100, "duracaoMeses": 1}""".formatted(UUID.randomUUID()))
                .andExpect(status().isCreated()));
        String nome = jdbc.queryForObject("SELECT nome FROM planos WHERE id = ?", String.class, plano);
        // NOT VALID: vale só para linhas novas (outros testes já deixaram edições na tabela).
        jdbc.execute("ALTER TABLE auditoria ADD CONSTRAINT ck_teste_recusa_edicao CHECK (acao <> 'EDITADO') NOT VALID");
        try {
            assertThatThrownBy(() -> enviar(put("/api/planos/" + plano).header("If-Match", "\"0\""), """
                    {"nome": "%s", "valorMensal": 999, "duracaoMeses": 1}""".formatted(nome)))
                    .hasStackTraceContaining("ck_teste_recusa_edicao");
        } finally {
            jdbc.execute("ALTER TABLE auditoria DROP CONSTRAINT ck_teste_recusa_edicao");
        }

        assertThat(jdbc.queryForObject("SELECT valor_mensal FROM planos WHERE id = ?", String.class, plano))
                .isEqualTo("100.00");
    }

    @Test
    void foraDeUmaTransacaoORegistroERecusado() {
        assertThatThrownBy(() -> auditoria.registrar(Auditoria.Entidade.PLANO, UUID.randomUUID(), "EDITADO",
                Map.of(), Map.of())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void soOAdminConsulta() throws Exception {
        for (String perfil : List.of("RECEPCAO", "INSTRUTOR", "ALUNO")) {
            mvc.perform(get("/api/auditoria").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + perfil))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/auditoria")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auditoria").param("entidade", "OUTRA").with(comoAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("Exception"))));
        mvc.perform(get("/api/auditoria").param("tamanho", "101").with(comoAdmin())).andExpect(status().isBadRequest());
    }
}
