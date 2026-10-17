package br.com.academiagestao.matriculas;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.auth.Perfil;
import br.com.academiagestao.auth.Usuario;
import br.com.academiagestao.auth.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A API de matrículas pela recepção, com token de verdade (ADR 0016). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatriculaControllerTest {

    private static final String SENHA = "senha-de-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private UUID recepcaoId;
    private UUID adminId;
    private String tokenRecepcao;
    private String tokenAdmin;
    private final List<UUID> alunos = new ArrayList<>();
    private final List<UUID> planos = new ArrayList<>();
    private UUID aluno;
    private UUID plano;

    @BeforeAll
    void prepararEquipe() throws Exception {
        String hash = passwordEncoder.encode(SENHA);
        String sufixo = UUID.randomUUID().toString();
        Usuario recepcao = usuarioRepository.save(
                Usuario.daEquipe("Recepção", "recepcao-" + sufixo + "@teste.local", hash, Perfil.RECEPCAO));
        Usuario admin = usuarioRepository.save(
                Usuario.daEquipe("Admin", "admin-" + sufixo + "@teste.local", hash, Perfil.ADMIN));
        recepcaoId = recepcao.getId();
        adminId = admin.getId();
        tokenRecepcao = login(recepcao.getEmail());
        tokenAdmin = login(admin.getEmail());
    }

    private String login(String email) throws Exception {
        String corpo = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.token");
    }

    @BeforeEach
    void prepararAlunoEPlano() {
        aluno = criarAluno(true);
        plano = criarPlano("129.90", 1, true);
    }

    /** Não deixa nada para trás: os outros testes rodam no mesmo banco, em ordem imprevisível. */
    @AfterEach
    void limparAlunosEPlanos() {
        for (UUID id : alunos) {
            jdbc.update("DELETE FROM trancamentos WHERE matricula_id IN (SELECT id FROM matriculas WHERE aluno_id = ?)",
                    id);
            jdbc.update("DELETE FROM matriculas WHERE aluno_id = ?", id);
            jdbc.update("DELETE FROM alunos WHERE id = ?", id);
        }
        for (UUID id : planos) {
            jdbc.update("DELETE FROM planos WHERE id = ?", id);
        }
        alunos.clear();
        planos.clear();
    }

    @AfterAll
    void limparEquipe() {
        jdbc.update("DELETE FROM usuarios WHERE id IN (?, ?)", recepcaoId, adminId);
    }

    private UUID criarAluno(boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, ativo) VALUES (?, 'Aluno de teste', ?, ?)",
                id, CpfsParaTeste.aleatorio(), ativo);
        alunos.add(id);
        return id;
    }

    private UUID criarPlano(String valor, int meses, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses, ativo) VALUES (?, ?, ?::numeric, ?, ?)",
                id, "Plano " + id, valor, meses, ativo);
        planos.add(id);
        return id;
    }

    private static LocalDate hoje() {
        return LocalDate.now(Matricula.FUSO);
    }

    private ResultActions matricular(UUID alunoId, UUID planoId, LocalDate inicio) throws Exception {
        String corpoInicio = inicio == null ? "" : ", \"inicio\": \"" + inicio + "\"";
        return mvc.perform(post("/api/matriculas").header("Authorization", "Bearer " + tokenRecepcao)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alunoId\": \"%s\", \"planoId\": \"%s\"%s}".formatted(alunoId, planoId, corpoInicio)));
    }

    private String idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions cancelar(String id, String corpo) throws Exception {
        return mvc.perform(post("/api/matriculas/" + id + "/cancelar").header("Authorization", "Bearer " + tokenRecepcao)
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    @Test
    void matriculaSemInicioComecaHojeECopiaOPlano() throws Exception {
        UUID trimestral = criarPlano("119.90", 3, true);

        String id = idDe(matricular(aluno, trimestral, null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/matriculas/")))
                .andExpect(jsonPath("$.alunoId").value(aluno.toString()))
                .andExpect(jsonPath("$.planoId").value(trimestral.toString()))
                .andExpect(jsonPath("$.planoNome").value("Plano " + trimestral))
                .andExpect(jsonPath("$.valorMensal").value(119.90))
                .andExpect(jsonPath("$.duracaoMeses").value(3))
                .andExpect(jsonPath("$.valorTotal").value(359.70))
                .andExpect(jsonPath("$.inicio").value(hoje().toString()))
                .andExpect(jsonPath("$.fim").value(hoje().plusMonths(3).minusDays(1).toString()))
                .andExpect(jsonPath("$.situacao").value("VIGENTE"))
                .andExpect(jsonPath("$.canceladaEm").doesNotExist()));

        mvc.perform(get("/api/matriculas/" + id).header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("VIGENTE"));
    }

    @Test
    void valorSaiComDuasCasas() throws Exception {
        String corpo = matricular(aluno, criarPlano("100", 1, true), null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).contains("\"valorMensal\":100.00").contains("\"valorTotal\":100.00");
    }

    @Test
    void inicioFuturoFicaAgendada() throws Exception {
        matricular(aluno, plano, hoje().plusDays(10))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.situacao").value("AGENDADA"));
    }

    // O compromisso do ADR 0013: reajustar (ou renomear) o plano não muda o que o aluno já contratou.
    @Test
    void reajusteDoPlanoNaoMudaMatriculaExistente() throws Exception {
        String id = idDe(matricular(aluno, plano, null).andExpect(status().isCreated()));

        String etag = mvc.perform(get("/api/planos/" + plano).header("Authorization", "Bearer " + tokenAdmin))
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(put("/api/planos/" + plano).header("Authorization", "Bearer " + tokenAdmin)
                        .header("If-Match", etag).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"Novo %s\", \"valorMensal\": 149.90, \"duracaoMeses\": 2}".formatted(plano)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/matriculas/" + id).header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(jsonPath("$.planoNome").value("Plano " + plano))
                .andExpect(jsonPath("$.valorMensal").value(129.90))
                .andExpect(jsonPath("$.duracaoMeses").value(1))
                .andExpect(jsonPath("$.fim").value(hoje().plusMonths(1).minusDays(1).toString()));
        // Quem se matricula depois do reajuste paga o valor novo.
        matricular(criarAluno(true), plano, null)
                .andExpect(jsonPath("$.planoNome").value("Novo " + plano))
                .andExpect(jsonPath("$.valorMensal").value(149.90))
                .andExpect(jsonPath("$.duracaoMeses").value(2));
    }

    @Test
    void inicioNoPassadoEhRecusado() throws Exception {
        matricular(aluno, plano, hoje().minusDays(1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("passado")));
    }

    @Test
    void alunoOuPlanoInexistenteEhRecusado() throws Exception {
        matricular(UUID.randomUUID(), plano, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("aluno")));
        matricular(aluno, UUID.randomUUID(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("plano")));
    }

    @Test
    void alunoInativoNaoEhMatriculado() throws Exception {
        matricular(criarAluno(false), plano, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Aluno inativo"));
    }

    @Test
    void planoInativoNaoEhContratado() throws Exception {
        matricular(aluno, criarPlano("99.90", 1, false), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Plano inativo"));
    }

    @Test
    void camposObrigatorios() throws Exception {
        mvc.perform(post("/api/matriculas").header("Authorization", "Bearer " + tokenRecepcao)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[?(@.campo == 'alunoId')]").exists())
                .andExpect(jsonPath("$.erros[?(@.campo == 'planoId')]").exists());
        mvc.perform(post("/api/matriculas").header("Authorization", "Bearer " + tokenRecepcao)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alunoId\": \"nao-e-uuid\", \"planoId\": \"%s\"}".formatted(plano)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/matriculas").header("Authorization", "Bearer " + tokenRecepcao)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alunoId\": \"%s\", \"planoId\": \"%s\", \"inicio\": \"31/12/2026\"}".formatted(aluno, plano)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void periodoSobrepostoEhRecusadoComADataLivre() throws Exception {
        matricular(aluno, plano, null).andExpect(status().isCreated());
        LocalDate fim = hoje().plusMonths(1).minusDays(1);

        matricular(aluno, plano, fim)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Período ocupado"))
                .andExpect(jsonPath("$.detail").value(containsString(
                        "a partir de " + fim.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))));
        // A renovação no dia seguinte ao fim é aceita.
        matricular(aluno, plano, fim.plusDays(1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.situacao").value("AGENDADA"));
    }

    @Test
    void novaQueTerminaNoDiaEmQueAAgendadaComecaEhRecusada() throws Exception {
        LocalDate fimDaNova = hoje().plusMonths(1).minusDays(1);
        matricular(aluno, plano, fimDaNova).andExpect(status().isCreated());

        matricular(aluno, plano, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("a partir de")));
    }

    @Test
    void cancelarGuardaQuemEPorQue() throws Exception {
        String id = idDe(matricular(aluno, plano, null));

        cancelar(id, "{\"motivo\": \"  Mudou de cidade \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("CANCELADA"))
                .andExpect(jsonPath("$.canceladaPor").value(recepcaoId.toString()))
                .andExpect(jsonPath("$.motivoCancelamento").value("Mudou de cidade"))
                .andExpect(jsonPath("$.canceladaEm").exists());

        // Cancelada não ocupa o período: o aluno pode ser matriculado de novo.
        matricular(aluno, plano, null).andExpect(status().isCreated());
    }

    @Test
    void naoCancelaDuasVezes() throws Exception {
        String id = idDe(matricular(aluno, plano, null));
        cancelar(id, "{\"motivo\": \"Primeiro\"}").andExpect(status().isOk());

        cancelar(id, "{\"motivo\": \"Segundo\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("cancelada")));
    }

    @Test
    void naoCancelaEncerrada() throws Exception {
        // Uma matrícula do passado só existe por fora da API (a API recusa início no passado).
        UUID id = UUID.randomUUID();
        LocalDate inicio = hoje().minusMonths(2);
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'Mensal', 129.90, 1, ?, ?, now())""",
                id, aluno, plano, Date.valueOf(inicio), Date.valueOf(inicio.plusMonths(1).minusDays(1)));

        mvc.perform(get("/api/matriculas/" + id).header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(jsonPath("$.situacao").value("ENCERRADA"));
        cancelar(id.toString(), "{\"motivo\": \"Tarde demais\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Matrícula não pode ser cancelada"));
    }

    @Test
    void cancelarExigeMotivo() throws Exception {
        String id = idDe(matricular(aluno, plano, null));

        cancelar(id, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("motivo"));
        cancelar(id, "{\"motivo\": \"   \"}").andExpect(status().isBadRequest());
        cancelar(id, "{\"motivo\": \"%s\"}".formatted("x".repeat(501))).andExpect(status().isBadRequest());
    }

    @Test
    void matriculaInexistente() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/matriculas/" + id).header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Matrícula não encontrada"));
        cancelar(id.toString(), "{\"motivo\": \"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void historicoDoAlunoDaMaisRecenteParaAMaisAntiga() throws Exception {
        String cancelada = idDe(matricular(aluno, plano, hoje().plusMonths(3)));
        cancelar(cancelada, "{\"motivo\": \"Desistiu\"}").andExpect(status().isOk());
        String atual = idDe(matricular(aluno, plano, null));
        String renovacao = idDe(matricular(aluno, plano, hoje().plusMonths(1)));
        matricular(criarAluno(true), plano, null).andExpect(status().isCreated());  // de outro aluno

        mvc.perform(get("/api/matriculas").param("alunoId", aluno.toString())
                        .header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(cancelada))
                .andExpect(jsonPath("$[0].situacao").value("CANCELADA"))
                .andExpect(jsonPath("$[1].id").value(renovacao))
                .andExpect(jsonPath("$[2].id").value(atual));
    }

    @Test
    void listarExigeOAluno() throws Exception {
        mvc.perform(get("/api/matriculas").header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/matriculas").param("alunoId", "x").header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(status().isBadRequest());
    }

    @Test
    void locationApontaParaAMatricula() throws Exception {
        ResultActions resultado = matricular(aluno, plano, null);
        resultado.andExpect(header().string("Location", endsWith("/api/matriculas/" + idDe(resultado))));
    }

    // Fidelidade e multa (ADR 0018)

    private UUID criarPlanoComFidelidade(String valor, int meses, String percentual) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO planos (id, nome, valor_mensal, duracao_meses, fidelidade, percentual_multa, forma_pagamento)
                VALUES (?, ?, ?::numeric, ?, true, ?::numeric, 'A_VISTA')""", id, "Plano " + id, valor, meses, percentual);
        planos.add(id);
        return id;
    }

    @Test
    void matriculaCopiaAFidelidadeECancelarCalculaAMulta() throws Exception {
        UUID anual = criarPlanoComFidelidade("99.90", 12, "20");

        String id = idDe(matricular(aluno, anual, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fidelidade").value(true))
                .andExpect(jsonPath("$.percentualMulta").value(20.00))
                .andExpect(jsonPath("$.formaPagamento").value("A_VISTA"))
                .andExpect(jsonPath("$.multaCancelamento").doesNotExist()));

        // Cancelada no primeiro dia: o 1º mês já está em curso; faltam 11. 11 × 99,90 × 20% = 219,78.
        cancelar(id, "{\"motivo\": \"Mudou de cidade\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.multaCancelamento").value(219.78));
    }

    @Test
    void mudarAFidelidadeDoPlanoNaoMudaOContrato() throws Exception {
        UUID anual = criarPlanoComFidelidade("99.90", 12, "20");
        String id = idDe(matricular(aluno, anual, null));

        jdbc.update("UPDATE planos SET fidelidade = false, percentual_multa = NULL, forma_pagamento = 'MENSAL'"
                + " WHERE id = ?", anual);

        mvc.perform(get("/api/matriculas/" + id).header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(jsonPath("$.fidelidade").value(true))
                .andExpect(jsonPath("$.percentualMulta").value(20.00))
                .andExpect(jsonPath("$.formaPagamento").value("A_VISTA"));
    }

    @Test
    void semFidelidadeCancelaSemMulta() throws Exception {
        String id = idDe(matricular(aluno, plano, null));

        cancelar(id, "{\"motivo\": \"Desistiu\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fidelidade").value(false))
                .andExpect(jsonPath("$.multaCancelamento").doesNotExist());
    }

    // Trancamento (ADR 0018)

    private ResultActions trancar(String id, LocalDate inicio, LocalDate volta) throws Exception {
        String corpoInicio = inicio == null ? "" : "\"inicio\": \"" + inicio + "\", ";
        return mvc.perform(post("/api/matriculas/" + id + "/trancar").header("Authorization", "Bearer " + tokenRecepcao)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{%s\"voltaPrevista\": \"%s\", \"motivo\": \"Viagem\"}".formatted(corpoInicio, volta)));
    }

    @Test
    void trancarEstendeOFimEMostraOTrancamento() throws Exception {
        String id = idDe(matricular(aluno, plano, null));
        LocalDate fim = hoje().plusMonths(1).minusDays(1);

        trancar(id, null, hoje().plusDays(30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("TRANCADA"))
                .andExpect(jsonPath("$.diasTrancados").value(30))
                .andExpect(jsonPath("$.fim").value(fim.plusDays(30).toString()))
                .andExpect(jsonPath("$.trancamentos[0].inicio").value(hoje().toString()))
                .andExpect(jsonPath("$.trancamentos[0].voltaPrevista").value(hoje().plusDays(30).toString()))
                .andExpect(jsonPath("$.trancamentos[0].dias").value(30))
                .andExpect(jsonPath("$.trancamentos[0].motivo").value("Viagem"));
    }

    @Test
    void comRenovacaoAgendadaOTrancamentoEhRecusado() throws Exception {
        String id = idDe(matricular(aluno, plano, null));
        LocalDate renovacao = hoje().plusMonths(1);
        matricular(aluno, plano, renovacao).andExpect(status().isCreated());

        trancar(id, null, hoje().plusDays(10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Trancamento recusado"))
                .andExpect(jsonPath("$.detail").value(containsString("renovação agendada")))
                .andExpect(jsonPath("$.detail").value(containsString("cancele")));
        mvc.perform(get("/api/matriculas/" + id).header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(jsonPath("$.diasTrancados").value(0))
                .andExpect(jsonPath("$.trancamentos.length()").value(0));
    }

    @Test
    void registrarAVoltaContaSoOsDiasParados() throws Exception {
        String id = idDe(matricular(aluno, plano, null));
        trancar(id, hoje().plusDays(5), hoje().plusDays(20)).andExpect(status().isOk());

        // Ainda não começou: registrar a volta desfaz o trancamento.
        mvc.perform(post("/api/matriculas/" + id + "/registrar-volta").header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diasTrancados").value(0))
                .andExpect(jsonPath("$.situacao").value("VIGENTE"))
                .andExpect(jsonPath("$.trancamentos[0].dias").value(0));
        mvc.perform(post("/api/matriculas/" + id + "/registrar-volta").header("Authorization", "Bearer " + tokenRecepcao))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Volta recusada"));
    }

    @Test
    void dadosDoTrancamentoInvalidos() throws Exception {
        String id = idDe(matricular(aluno, plano, null));

        trancar(id, null, hoje().plusDays(6)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("7 a 90")));
        mvc.perform(post("/api/matriculas/" + id + "/trancar").header("Authorization", "Bearer " + tokenRecepcao)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[?(@.campo == 'voltaPrevista')]").exists())
                .andExpect(jsonPath("$.erros[?(@.campo == 'motivo')]").exists());
        trancar(UUID.randomUUID().toString(), null, hoje().plusDays(10)).andExpect(status().isNotFound());
    }
}
