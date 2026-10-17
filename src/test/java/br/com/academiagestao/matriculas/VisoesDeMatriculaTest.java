package br.com.academiagestao.matriculas;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O que o instrutor e o aluno veem das matrículas (ADR 0016). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class VisoesDeMatriculaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> alunos = new ArrayList<>();
    private UUID plano;
    private UUID usuario;

    @BeforeEach
    void preparar() {
        plano = UUID.randomUUID();
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 129.90, 1)",
                plano, "Plano " + plano);
        usuario = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Recepção', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
    }

    @AfterEach
    void limpar() {
        for (UUID id : alunos) {
            jdbc.update("DELETE FROM matriculas WHERE aluno_id = ?", id);
            jdbc.update("DELETE FROM alunos WHERE id = ?", id);
        }
        alunos.clear();
        jdbc.update("DELETE FROM usuarios WHERE id = ?", usuario);
        jdbc.update("DELETE FROM planos WHERE id = ?", plano);
    }

    private UUID criarAluno(boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, ativo) VALUES (?, 'Aluno', ?, ?)",
                id, CpfsParaTeste.aleatorio(), ativo);
        alunos.add(id);
        return id;
    }

    private static LocalDate hoje() {
        return LocalDate.now(Matricula.FUSO);
    }

    /** Grava direto: matrículas no passado não passam pela API. */
    private UUID matricula(UUID aluno, LocalDate inicio, boolean cancelada) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em,
                                        cancelada_em, cancelada_por, motivo_cancelamento)
                VALUES (?, ?, ?, 'Mensal', 129.90, 1, ?, ?, now(), ?, ?, ?)""",
                id, aluno, plano, Date.valueOf(inicio), Date.valueOf(inicio.plusMonths(1).minusDays(1)),
                cancelada ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
                cancelada ? usuario : null, cancelada ? "Desistiu" : null);
        return id;
    }

    private static RequestPostProcessor token(String perfil) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("perfil", perfil))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private static RequestPostProcessor tokenDeAluno(UUID alunoId) {
        return jwt().jwt(j -> {
                    j.subject(UUID.randomUUID().toString()).claim("perfil", "ALUNO");
                    if (alunoId != null) {
                        j.claim("alunoId", alunoId.toString());
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_ALUNO"));
    }

    private boolean vigenteParaOInstrutor(UUID aluno) throws Exception {
        String corpo = mvc.perform(get("/api/matriculas/vigente").param("alunoId", aluno.toString())
                        .with(token("INSTRUTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alunoId").value(aluno.toString()))
                .andReturn().getResponse().getContentAsString();
        // Só o id e o sim/não: nada de valor, plano ou datas.
        assertThat(corpo).doesNotContain("valor").doesNotContain("plano").doesNotContain("inicio");
        return corpo.contains("\"matriculaVigente\":true");
    }

    @Test
    void instrutorVeQueOAlunoPodeTreinar() throws Exception {
        UUID comecaHoje = criarAluno(true);
        matricula(comecaHoje, hoje(), false);
        UUID terminaHoje = criarAluno(true);
        matricula(terminaHoje, hoje().minusMonths(1).plusDays(1), false);  // fim = hoje

        assertThat(vigenteParaOInstrutor(comecaHoje)).isTrue();
        assertThat(vigenteParaOInstrutor(terminaHoje)).isTrue();
    }

    @Test
    void instrutorVeQueOAlunoNaoPodeTreinar() throws Exception {
        UUID semMatricula = criarAluno(true);
        UUID soAgendada = criarAluno(true);
        matricula(soAgendada, hoje().plusDays(1), false);
        UUID encerrada = criarAluno(true);
        matricula(encerrada, hoje().minusMonths(1), false);  // fim = ontem
        UUID cancelada = criarAluno(true);
        matricula(cancelada, hoje(), true);

        assertThat(vigenteParaOInstrutor(semMatricula)).isFalse();
        assertThat(vigenteParaOInstrutor(soAgendada)).isFalse();
        assertThat(vigenteParaOInstrutor(encerrada)).isFalse();
        assertThat(vigenteParaOInstrutor(cancelada)).isFalse();
        assertThat(vigenteParaOInstrutor(UUID.randomUUID())).isFalse();
    }

    @Test
    void alunoVeSoAsProprias() throws Exception {
        UUID ana = criarAluno(true);
        UUID cancelada = matricula(ana, hoje().minusMonths(1), true);
        UUID atual = matricula(ana, hoje(), false);
        UUID bia = criarAluno(true);
        matricula(bia, hoje(), false);

        mvc.perform(get("/api/matriculas/me").with(tokenDeAluno(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(atual.toString()))
                .andExpect(jsonPath("$[0].situacao").value("VIGENTE"))
                .andExpect(jsonPath("$[0].valorMensal").value(129.90))
                .andExpect(jsonPath("$[0].planoNome").value("Mensal"))
                .andExpect(jsonPath("$[1].id").value(cancelada.toString()))
                .andExpect(jsonPath("$[1].situacao").value("CANCELADA"))
                .andExpect(jsonPath("$[1].motivoCancelamento").value("Desistiu"))
                // Quem da equipe cancelou é dado interno.
                .andExpect(jsonPath("$[1].canceladaPor").doesNotExist())
                .andExpect(jsonPath("$[1].canceladaEm").exists())
                .andExpect(jsonPath("$[0].fidelidade").value(false))
                .andExpect(jsonPath("$[0].formaPagamento").value("MENSAL"));
    }

    @Test
    void alunoInativoContinuaVendoAsProprias() throws Exception {
        UUID inativo = criarAluno(false);
        matricula(inativo, hoje().minusMonths(2), false);

        mvc.perform(get("/api/matriculas/me").with(tokenDeAluno(inativo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].situacao").value("ENCERRADA"));
    }

    @Test
    void tokenDeAlunoSemAlunoIdEhRecusado() throws Exception {
        mvc.perform(get("/api/matriculas/me").with(tokenDeAluno(null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void alunoVeAMultaQueLheFoiCobrada() throws Exception {
        UUID ana = criarAluno(true);
        LocalDate inicio = hoje().minusMonths(2);
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim,
                                        criada_em, fidelidade, percentual_multa, forma_pagamento, cancelada_em,
                                        cancelada_por, motivo_cancelamento, multa_cancelamento)
                VALUES (?, ?, ?, 'Anual', 99.90, 12, ?, ?, now(), true, 20, 'MENSAL', now(), ?, 'Mudou de cidade', 179.82)""",
                UUID.randomUUID(), ana, plano, Date.valueOf(inicio), Date.valueOf(inicio.plusMonths(12).minusDays(1)),
                usuario);

        mvc.perform(get("/api/matriculas/me").with(tokenDeAluno(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fidelidade").value(true))
                .andExpect(jsonPath("$[0].percentualMulta").value(20.00))
                .andExpect(jsonPath("$[0].multaCancelamento").value(179.82));
    }
}
