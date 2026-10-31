package br.com.academiagestao.pagamentos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.RelogioDeTeste;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O boleto gerado no balcão (ADR 0047, decisão 4): a recepção e o ADMIN geram, para uma cobrança em aberto
 * do aluno, o mesmo boleto do app, com a empresa simulada.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"academia.pagamentos.provedor=simulado",
        "academia.pagamentos.token-do-aviso-simulado=token-de-teste-do-aviso",
        "academia.pagamentos.intervalo-da-consulta=1h"})
@AutoConfigureMockMvc
class BoletoNoBalcaoTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    RelogioDeTeste relogio;

    private final UUID aluno = UUID.randomUUID();
    private final UUID outroAluno = UUID.randomUUID();
    private final UUID recepcao = UUID.randomUUID();
    private final UUID usuarioDoAluno = UUID.randomUUID();
    private final UUID plano = UUID.randomUUID();
    private final UUID matricula = UUID.randomUUID();
    private final UUID matriculaDoOutro = UUID.randomUUID();
    private LocalDate hoje;

    @BeforeEach
    void preparar() {
        hoje = Calendario.hoje(Instant.now()).plusDays(1);
        relogio.fixar(hoje.atTime(12, 0).atZone(Calendario.FUSO).toInstant());
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email) VALUES (?, 'Aluna Balcão', ?, ?)", aluno,
                CpfsParaTeste.aleatorio(), aluno + "@teste.local");
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Outro', ?)", outroAluno, CpfsParaTeste.aleatorio());
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Rita', ?, 'x', 'RECEPCAO')",
                recepcao, recepcao + "@teste.local");
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, aluno_id) VALUES (?, 'Aluna', ?, 'x', "
                + "'ALUNO', ?)", usuarioDoAluno, usuarioDoAluno + "@teste.local", aluno);
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 3)", plano, "P " + plano);
        for (UUID[] m : new UUID[][] {{matricula, aluno}, {matriculaDoOutro, outroAluno}}) {
            LocalDate inicio = hoje.minusDays(30);
            jdbc.update("""
                    INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim,
                                            criada_em)
                    VALUES (?, ?, ?, 'P', 100, 3, ?, ?, now())""", m[0], m[1], plano, inicio,
                    inicio.plusMonths(3).minusDays(1));
        }
    }

    @AfterEach
    void limpar() {
        relogio.restaurar();
        jdbc.update("DELETE FROM auditoria WHERE entidade_id IN (SELECT id FROM pagamentos_online WHERE aluno_id = ?) "
                + "OR entidade_id IN (SELECT id FROM cobrancas WHERE aluno_id IN (?, ?))", aluno, aluno, outroAluno);
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("alunos", outroAluno);
        limpeza.apagar("planos", plano);
        limpeza.apagar("usuarios", usuarioDoAluno);
        limpeza.apagar("usuarios", recepcao);
        jdbc.update("UPDATE configuracao_pagamento_online SET pix_ligado = true, boleto_ligado = true, "
                + "credito_ligado = true, debito_ligado = true, adiantar = 'PROXIMA', dias_do_boleto = 3");
    }

    /** Uma cobrança de 100,00 vencendo {@code diasAtras} dias antes de hoje (negativo: no futuro). */
    private UUID cobranca(UUID matriculaId, UUID alunoId, int parcela, int diasAtras) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cobrancas (id, matricula_id, aluno_id, tipo, parcela, valor, vencimento, status)
                VALUES (?, ?, ?, 'MENSALIDADE', ?, 100, ?, 'PENDENTE')""", id, matriculaId, alunoId, parcela,
                hoje.minusDays(diasAtras));
        return id;
    }

    private static RequestPostProcessor como(String perfil, UUID usuario) {
        return jwt().jwt(j -> j.subject(usuario.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private ResultActions gerar(RequestPostProcessor quem, UUID alunoId, UUID cobrancaId) throws Exception {
        return mvc.perform(post("/api/pagamentos-online/balcao/boleto").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alunoId\": \"%s\", \"cobrancaId\": \"%s\"}".formatted(alunoId, cobrancaId)));
    }

    @Test
    void aRecepcaoGeraOBoletoComOValorDeHojeEPedirDeNovoDevolveOMesmo() throws Exception {
        UUID atrasada = cobranca(matricula, aluno, 1, 30);

        String corpo = gerar(como("RECEPCAO", recepcao), aluno, atrasada).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.forma").value("BOLETO"))
                .andExpect(jsonPath("$.total").value(103.0))
                .andExpect(jsonPath("$.boletoLinhaDigitavel").isNotEmpty())
                .andExpect(jsonPath("$.situacao").value("AGUARDANDO"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(corpo, "$.id");

        assertThat(jdbc.queryForObject("SELECT gerado_por FROM pagamentos_online WHERE id = ?::uuid", UUID.class, id))
                .isEqualTo(recepcao);
        gerar(como("ADMIN", recepcao), aluno, atrasada).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamentos_online WHERE cobranca_id = ?", Integer.class,
                atrasada)).isEqualTo(1);
        // O aluno vê no app o mesmo boleto.
        mvc.perform(get("/api/pagamentos-online/me").with(jwt().jwt(j -> j.subject(usuarioDoAluno.toString())
                        .claim("alunoId", aluno.toString())).authorities(new SimpleGrantedAuthority("ROLE_ALUNO"))))
                .andExpect(jsonPath("$.pagaveis[0].boleto.id").value(id));
    }

    /** O app só deixa adiantar a próxima; o balcão gera para qualquer uma em aberto, como o recebimento. */
    @Test
    void oBalcaoGeraParaUmaQueOAppAindaNaoDeixaPagar() throws Exception {
        cobranca(matricula, aluno, 1, -5);
        UUID terceira = cobranca(matricula, aluno, 2, -35);

        mvc.perform(post("/api/pagamentos-online/me").with(jwt().jwt(j -> j.subject(usuarioDoAluno.toString())
                                .claim("alunoId", aluno.toString())).authorities(new SimpleGrantedAuthority("ROLE_ALUNO")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cobrancaId\": \"" + terceira + "\", \"forma\": \"BOLETO\"}"))
                .andExpect(status().isConflict());
        gerar(como("RECEPCAO", recepcao), aluno, terceira).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(100.0));
    }

    @Test
    void cobrancaDeOutroAlunoPagaOuInexistenteDa404() throws Exception {
        UUID doOutro = cobranca(matriculaDoOutro, outroAluno, 1, 3);
        UUID paga = cobranca(matricula, aluno, 1, 3);
        jdbc.update("UPDATE cobrancas SET status = 'CANCELADA', cancelada_em = now(), motivo_cancelamento = 'teste' WHERE id = ?", paga);

        gerar(como("RECEPCAO", recepcao), aluno, doOutro).andExpect(status().isNotFound());
        gerar(como("RECEPCAO", recepcao), aluno, paga).andExpect(status().isNotFound());
        gerar(como("RECEPCAO", recepcao), aluno, UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamentos_online WHERE aluno_id IN (?, ?)", Integer.class,
                aluno, outroAluno)).isZero();
    }

    @Test
    void comOBoletoDesligadoNaConfiguracaoOBalcaoNaoOferece() throws Exception {
        UUID atrasada = cobranca(matricula, aluno, 1, 3);
        mvc.perform(get("/api/pagamentos-online/balcao").with(como("RECEPCAO", recepcao)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.boleto").value(true));

        jdbc.update("UPDATE configuracao_pagamento_online SET boleto_ligado = false");

        mvc.perform(get("/api/pagamentos-online/balcao").with(como("RECEPCAO", recepcao)))
                .andExpect(jsonPath("$.boleto").value(false));
        gerar(como("RECEPCAO", recepcao), aluno, atrasada).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("O boleto não está disponível"));
    }

    @ParameterizedTest(name = "{0} -> 403")
    @CsvSource({"INSTRUTOR", "ALUNO"})
    void soARecepcaoEOAdmin(String perfil) throws Exception {
        UUID atrasada = cobranca(matricula, aluno, 1, 3);
        mvc.perform(get("/api/pagamentos-online/balcao").with(como(perfil, UUID.randomUUID()))).andExpect(status().isForbidden());
        gerar(como(perfil, UUID.randomUUID()), aluno, atrasada).andExpect(status().isForbidden());
    }

    @Test
    void pedidoSemOAlunoOuACobrancaDa400() throws Exception {
        mvc.perform(post("/api/pagamentos-online/balcao/boleto").with(como("RECEPCAO", recepcao))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erros.length()").value(2));
    }

    private RequestPostProcessor aluno(UUID alunoId) {
        // A própria aluna entra com o login dela (o Pix gerado guarda quem gerou); a outra, com um qualquer.
        UUID login = alunoId.equals(aluno) ? usuarioDoAluno : UUID.randomUUID();
        return jwt().jwt(j -> j.subject(login.toString()).claim("alunoId", alunoId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ALUNO"));
    }

    /**
     * No simulado, o "PDF do boleto" é uma tela do app (/boleto-simulado/{id}), que lê o boleto por esta rota: o aluno vê
     * os dele, a recepção e o ADMIN veem os que geraram no balcão; o Pix não é boleto.
     */
    @Test
    void oBoletoSimuladoTemOLinkDaTelaEQuemPodeVer() throws Exception {
        UUID atrasada = cobranca(matricula, aluno, 1, 30);
        String corpo = gerar(como("RECEPCAO", recepcao), aluno, atrasada).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(corpo, "$.id");
        assertThat((String) JsonPath.read(corpo, "$.boletoUrl")).isEqualTo("/boleto-simulado/" + id);
        String rota = "/api/pagamentos-online/" + id + "/boleto-simulado";

        mvc.perform(get(rota).with(como("RECEPCAO", recepcao))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.total").value(103.0))
                .andExpect(jsonPath("$.boletoLinhaDigitavel").value((String) JsonPath.read(corpo, "$.boletoLinhaDigitavel")))
                .andExpect(jsonPath("$.validoAte").isNotEmpty());
        mvc.perform(get(rota).with(como("ADMIN", UUID.randomUUID()))).andExpect(status().isOk());
        mvc.perform(get(rota).with(aluno(aluno))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get(rota).with(aluno(outroAluno))).andExpect(status().isNotFound());
        mvc.perform(get(rota).with(como("INSTRUTOR", UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/pagamentos-online/" + UUID.randomUUID() + "/boleto-simulado").with(como("RECEPCAO", recepcao)))
                .andExpect(status().isNotFound());

        // O Pix não tem tela de boleto.
        UUID outra = cobranca(matricula, aluno, 2, 3);
        String pix = mvc.perform(post("/api/pagamentos-online/me").with(aluno(aluno)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cobrancaId\": \"" + outra + "\", \"forma\": \"PIX\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/pagamentos-online/" + JsonPath.read(pix, "$.id") + "/boleto-simulado").with(aluno(aluno)))
                .andExpect(status().isNotFound());
    }
}
