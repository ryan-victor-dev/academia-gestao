package br.com.academiagestao.matriculas;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O "pode treinar?" diz não depois de N dias de atraso (padrão 10, editável; ADR 0024). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InadimplenciaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MatriculaService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    private final UUID aluno = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID usuario = UUID.randomUUID();
    private UUID matricula;
    private UUID primeira;
    private LocalDate hoje;

    @BeforeEach
    void preparar() {
        hoje = Calendario.hoje(Instant.now()).plusDays(1);
        relogio.fixar(hoje.atTime(12, 0).atZone(Calendario.FUSO).toInstant());
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'R', ?, 'x', 'RECEPCAO')",
                usuario, usuario + "@teste.local");
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", aluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 3)", plano, "P " + plano);
        matricula = service.matricular(aluno, plano, null).getId();
        primeira = jdbc.queryForObject("SELECT id FROM cobrancas WHERE matricula_id = ? AND parcela = 1", UUID.class,
                matricula);
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("planos", plano);
        limpeza.apagar("usuarios", usuario);
        jdbc.update("UPDATE configuracao_cobranca SET dias_para_bloquear = 10");
    }

    /** A primeira mensalidade passa a ter vencido há {@code dias} dias. */
    private void atrasar(int dias) {
        jdbc.update("UPDATE cobrancas SET vencimento = ? WHERE id = ?", hoje.minusDays(dias), primeira);
    }

    private boolean podeTreinar() throws Exception {
        String corpo = mvc.perform(get("/api/matriculas/vigente").param("alunoId", aluno.toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INSTRUTOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alunoId").value(aluno.toString()))
                .andReturn().getResponse().getContentAsString();
        return corpo.contains("\"podeTreinar\":true");
    }

    @Test
    void emDiaTreina() throws Exception {
        assertThat(podeTreinar()).isTrue();
    }

    /** "Depois de 10 dias": com 10 dias de atraso ainda treina; com 11, não. */
    @Test
    void bloqueiaDepoisDeDezDias() throws Exception {
        atrasar(10);
        assertThat(podeTreinar()).isTrue();
        atrasar(11);
        assertThat(podeTreinar()).isFalse();
    }

    @Test
    void pagarDesbloqueia() throws Exception {
        atrasar(20);
        jdbc.update("""
                UPDATE cobrancas SET status = 'PAGA', pago_acrescimo = 2, pago_juros = 0.67, pago_total = 102.67,
                       meio_de_pagamento = 'PIX', paga_em = now(), paga_por = ? WHERE id = ?""", usuario, primeira);
        assertThat(podeTreinar()).isTrue();
    }

    @Test
    void osDiasSaoOsDaConfiguracao() throws Exception {
        atrasar(1);
        assertThat(podeTreinar()).isTrue();
        jdbc.update("UPDATE configuracao_cobranca SET dias_para_bloquear = 0");
        assertThat(podeTreinar()).isFalse();
    }

    /** A multa de uma matrícula anterior, se atrasada, também bloqueia (vale qualquer cobrança do aluno). */
    @Test
    void multaAtrasadaDeOutraMatriculaBloqueia() throws Exception {
        UUID anterior = UUID.randomUUID();
        LocalDate inicio = hoje.minusMonths(6);
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim,
                                        criada_em, cancelada_em, cancelada_por, motivo_cancelamento)
                VALUES (?, ?, ?, 'P', 100, 3, ?, ?, now(), now(), ?, 'Saiu')""",
                anterior, aluno, plano, inicio, inicio.plusMonths(3).minusDays(1), usuario);
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, ?, 'MULTA', 1, 50, ?, 'PENDENTE')""", UUID.randomUUID(), anterior, aluno, hoje.minusDays(40));

        assertThat(podeTreinar()).isFalse();
    }

    @Test
    void canceladaAtrasadaNaoBloqueia() throws Exception {
        atrasar(30);
        jdbc.update("UPDATE cobrancas SET status = 'CANCELADA', cancelada_em = now(), motivo_cancelamento = 'Bolsa' "
                + "WHERE id = ?", primeira);
        assertThat(podeTreinar()).isTrue();
    }

    @Test
    void outroAlunoAtrasadoNaoBloqueiaEste() throws Exception {
        UUID outro = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Outro', ?)", outro, CpfsParaTeste.aleatorio());
        try {
            UUID outraMatricula = service.matricular(outro, plano, null).getId();
            jdbc.update("UPDATE cobrancas SET vencimento = ? WHERE matricula_id = ? AND parcela = 1",
                    hoje.minusDays(30), outraMatricula);
            assertThat(podeTreinar()).isTrue();
        } finally {
            limpeza.apagar("alunos", outro);
        }
    }
}
