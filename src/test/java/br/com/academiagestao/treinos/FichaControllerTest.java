package br.com.academiagestao.treinos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.alunos.CpfsParaTeste;
import br.com.academiagestao.compartilhado.Calendario;
import com.jayway.jsonpath.JsonPath;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fichas de treino pela API (ADR 0027). As permissões por perfil estão em PermissoesDeFichasTest. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FichaControllerTest {

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock relogio;

    private UUID instrutorId;
    private RequestPostProcessor instrutor;
    private UUID aluno;
    private String supino;
    private String remada;

    @BeforeEach
    void preparar() {
        limpeza.apagarTudo();
        instrutorId = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Instrutor', ?, 'x', 'INSTRUTOR')",
                instrutorId, instrutorId + "@academia.com");
        instrutor = jwt().jwt(j -> j.subject(instrutorId.toString()).claim("perfil", "INSTRUTOR"))
                .authorities(new SimpleGrantedAuthority("ROLE_INSTRUTOR"));
        aluno = novoAluno(true);
        supino = novoExercicio("Supino reto", true);
        remada = novoExercicio("Remada curvada", true);
    }

    private UUID novoAluno(boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, ativo) VALUES (?, 'Aluno', ?, ?)", id,
                CpfsParaTeste.aleatorio(), ativo);
        return id;
    }

    private String novoExercicio(String nome, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO exercicios (id, nome, grupo_muscular, ativo, criado_em) VALUES (?, ?, 'PEITO', ?, now())",
                id, nome, ativo);
        return id.toString();
    }

    private static String item(String exercicioId, int series, String repeticoes, String carga) {
        return """
                {"exercicioId": "%s", "series": %d, "repeticoes": "%s", "carga": %s, "descansoSegundos": 60}"""
                .formatted(exercicioId, series, repeticoes, carga == null ? "null" : "\"" + carga + "\"");
    }

    private static String divisoes(String... itensDaDivisaoA) {
        return "[{\"nome\": \"A\", \"exercicios\": [" + String.join(", ", itensDaDivisaoA) + "]}]";
    }

    private ResultActions montar(UUID alunoId, String divisoes) throws Exception {
        return mvc.perform(post("/api/fichas").with(instrutor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alunoId\": \"%s\", \"divisoes\": %s}".formatted(alunoId, divisoes)));
    }

    private String montarFicha(UUID alunoId, String divisoes) throws Exception {
        return JsonPath.read(montar(alunoId, divisoes).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions corrigir(String id, String ifMatch, String corpo) throws Exception {
        var requisicao = put("/api/fichas/" + id).with(instrutor).contentType(MediaType.APPLICATION_JSON).content(corpo);
        if (ifMatch != null) {
            requisicao.header("If-Match", ifMatch);
        }
        return mvc.perform(requisicao);
    }

    private ResultActions ver(String caminho) throws Exception {
        return mvc.perform(get(caminho).with(instrutor));
    }

    @Test
    void instrutorMontaAFicha() throws Exception {
        mvc.perform(post("/api/fichas").with(instrutor).contentType(MediaType.APPLICATION_JSON).content("""
                        {"alunoId": "%s", "revisaoEm": "%s", "divisoes": [
                          {"nome": "A — peito", "exercicios": [%s, %s]},
                          {"nome": "B — costas", "exercicios": [%s]}]}"""
                        .formatted(aluno, Calendario.hoje(relogio.instant()).plusDays(30),
                                item(supino, 4, "8 a 12", "20 kg"), item(remada, 3, "10", null), item(remada, 4, "12", null))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/fichas/[0-9a-f-]{36}")))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.alunoId").value(aluno.toString()))
                .andExpect(jsonPath("$.atual").value(true))
                .andExpect(jsonPath("$.montadaPor").value(instrutorId.toString()))
                .andExpect(jsonPath("$.montadaEm").exists())
                .andExpect(jsonPath("$.revisaoVencida").value(false))
                .andExpect(jsonPath("$.corrigidaEm").value(nullValue()))
                .andExpect(jsonPath("$.encerradaEm").value(nullValue()))
                .andExpect(jsonPath("$.divisoes[*].nome", contains("A — peito", "B — costas")))
                .andExpect(jsonPath("$.divisoes[0].exercicios[*].nome", contains("Supino reto", "Remada curvada")))
                .andExpect(jsonPath("$.divisoes[0].exercicios[0].exercicioId").value(supino))
                .andExpect(jsonPath("$.divisoes[0].exercicios[0].series").value(4))
                .andExpect(jsonPath("$.divisoes[0].exercicios[0].repeticoes").value("8 a 12"))
                .andExpect(jsonPath("$.divisoes[0].exercicios[0].carga").value("20 kg"))
                .andExpect(jsonPath("$.divisoes[0].exercicios[0].descansoSegundos").value(60))
                .andExpect(jsonPath("$.divisoes[1].exercicios[*].nome", contains("Remada curvada")));
    }

    @Test
    void fichaNovaEncerraAAnteriorQueVaiParaOHistorico() throws Exception {
        String primeira = montarFicha(aluno, divisoes(item(supino, 3, "10", null)));
        String segunda = montarFicha(aluno, divisoes(item(remada, 3, "10", null)));

        ver("/api/fichas/atual?alunoId=" + aluno)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(segunda));
        ver("/api/fichas?alunoId=" + aluno)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(segunda, primeira)))
                .andExpect(jsonPath("$[*].atual", contains(true, false)))
                .andExpect(jsonPath("$[1].encerradaEm").exists())
                .andExpect(jsonPath("$[1].divisoes[0].exercicios[0].nome").value("Supino reto"));
        ver("/api/fichas/" + primeira).andExpect(status().isOk()).andExpect(jsonPath("$.atual").value(false));
        // As fichas de outro aluno não se misturam.
        UUID outro = novoAluno(true);
        String doOutro = montarFicha(outro, divisoes(item(supino, 3, "10", null)));
        ver("/api/fichas?alunoId=" + outro).andExpect(jsonPath("$[*].id", contains(doOutro)));
        ver("/api/fichas/atual?alunoId=" + aluno).andExpect(jsonPath("$.id").value(segunda));
    }

    @Test
    void semFichaAtual404() throws Exception {
        ver("/api/fichas/atual?alunoId=" + aluno)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ficha não encontrada"));
        ver("/api/fichas/" + UUID.randomUUID()).andExpect(status().isNotFound());
        ver("/api/fichas?alunoId=" + aluno).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void alunoInativoNaoGanhaFichaNovaMasContinuaVendoAsAntigas() throws Exception {
        String antiga = montarFicha(aluno, divisoes(item(supino, 3, "10", null)));
        jdbc.update("UPDATE alunos SET ativo = false WHERE id = ?", aluno);

        montar(aluno, divisoes(item(remada, 3, "10", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Aluno inativo"));
        ver("/api/fichas/atual?alunoId=" + aluno).andExpect(jsonPath("$.id").value(antiga));
        montar(UUID.randomUUID(), divisoes(item(supino, 3, "10", null))).andExpect(status().isBadRequest());
    }

    @Test
    void exercicioInativoOuInexistenteNaoEntraEFichaRecusadaNaoEncerraAAtual() throws Exception {
        String atual = montarFicha(aluno, divisoes(item(supino, 3, "10", null)));
        String inativo = novoExercicio("Crucifixo", false);

        montar(aluno, divisoes(item(remada, 3, "10", null), item(inativo, 3, "10", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Exercício inativo"))
                .andExpect(jsonPath("$.detail", matchesPattern(".*\"Crucifixo\" está inativo.*")));
        montar(aluno, divisoes(item(UUID.randomUUID().toString(), 3, "10", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", matchesPattern("divisão 1, exercício 1: exercício .* não encontrado")));
        // Revisão no dia da montagem: recusada pela Ficha, depois de passar pela validação da requisição.
        mvc.perform(post("/api/fichas").with(instrutor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alunoId\": \"%s\", \"revisaoEm\": \"%s\", \"divisoes\": %s}".formatted(aluno,
                                Calendario.hoje(relogio.instant()), divisoes(item(remada, 3, "10", null)))))
                .andExpect(status().isBadRequest());

        ver("/api/fichas?alunoId=" + aluno).andExpect(jsonPath("$[*].id", contains(atual)))
                .andExpect(jsonPath("$[0].atual").value(true));
    }

    @Test
    void dadosInvalidosDevolvem400ComOsCampos() throws Exception {
        mvc.perform(post("/api/fichas").with(instrutor).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[*].campo", hasItems("alunoId", "divisoes")));
        montar(aluno, """
                [{"nome": " ", "exercicios": []},
                 {"nome": "B", "exercicios": [{"series": 0, "repeticoes": "", "carga": "%s", "descansoSegundos": 601,
                                               "observacao": "%s"}]}]"""
                .formatted("x".repeat(31), "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[*].campo", hasItems("divisoes[0].nome", "divisoes[0].exercicios",
                        "divisoes[1].exercicios[0].exercicioId", "divisoes[1].exercicios[0].series",
                        "divisoes[1].exercicios[0].repeticoes", "divisoes[1].exercicios[0].carga",
                        "divisoes[1].exercicios[0].descansoSegundos", "divisoes[1].exercicios[0].observacao")));
        String oito = String.join(", ", java.util.Collections.nCopies(8,
                "{\"nome\": \"A\", \"exercicios\": [%s]}".formatted(item(supino, 3, "10", null))));
        montar(aluno, "[" + oito + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[*].campo", hasItems("divisoes")));
        ver("/api/fichas?alunoId=" + aluno).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void corrigirComEtagSemEtag428ComEtagAntigo412() throws Exception {
        String id = montarFicha(aluno, divisoes(item(supino, 3, "10", "20 kg")));
        String correcao = "{\"divisoes\": %s}".formatted(divisoes(item(supino, 3, "10", "25 kg"), item(remada, 3, "12", null)));

        corrigir(id, null, correcao).andExpect(status().isPreconditionRequired());
        corrigir(id, "\"0\"", correcao)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.atual").value(true))
                .andExpect(jsonPath("$.corrigidaEm").exists())
                .andExpect(jsonPath("$.divisoes[0].exercicios[*].carga", contains("25 kg", null)));
        corrigir(id, "\"0\"", correcao)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.title").value("Ficha alterada por outra pessoa"));
        corrigir(UUID.randomUUID().toString(), "\"0\"", correcao).andExpect(status().isNotFound());
        // Correção, e não ficha nova: o histórico continua com uma só.
        ver("/api/fichas?alunoId=" + aluno).andExpect(jsonPath("$.length()").value(1));
        ver("/api/fichas/" + id).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.divisoes[0].exercicios[*].nome", contains("Supino reto", "Remada curvada")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ficha_divisoes", Integer.class))
                .as("as divisões antigas saem do banco").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ficha_exercicios", Integer.class)).isEqualTo(2);
    }

    @Test
    void fichaEncerradaNaoPodeSerCorrigida() throws Exception {
        String antiga = montarFicha(aluno, divisoes(item(supino, 3, "10", null)));
        montarFicha(aluno, divisoes(item(remada, 3, "10", null)));

        // Mesmo com "*" (qualquer versão): encerrada não muda.
        corrigir(antiga, "*", "{\"divisoes\": %s}".formatted(divisoes(item(remada, 5, "5", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Ficha encerrada"));
        // Com o ETag de antes do encerramento, a resposta diz o motivo real (encerrada), não 412.
        corrigir(antiga, "\"0\"", "{\"divisoes\": %s}".formatted(divisoes(item(remada, 5, "5", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Ficha encerrada"));
        ver("/api/fichas/" + antiga).andExpect(jsonPath("$.divisoes[0].exercicios[0].nome").value("Supino reto"));
    }

    @Test
    void correcaoMantemExercicioQueFoiInativadoMasNaoAceitaOutroInativo() throws Exception {
        String id = montarFicha(aluno, divisoes(item(supino, 3, "10", "20 kg")));
        jdbc.update("UPDATE exercicios SET ativo = false WHERE id = ?::uuid", supino);
        String outroInativo = novoExercicio("Crucifixo", false);

        corrigir(id, "\"0\"", "{\"divisoes\": %s}".formatted(divisoes(item(supino, 3, "10", "25 kg"))))
                .andExpect(status().isOk());
        corrigir(id, "\"1\"", "{\"divisoes\": %s}".formatted(divisoes(item(supino, 3, "10", null),
                item(outroInativo, 3, "10", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Exercício inativo"));
        // Ficha nova não aceita o que foi inativado, mesmo que estivesse na anterior.
        montar(aluno, divisoes(item(supino, 3, "10", null))).andExpect(status().isConflict());
    }

    @Test
    void renomearOExercicioNaoMudaAFicha() throws Exception {
        String id = montarFicha(aluno, divisoes(item(supino, 3, "10", null)));
        jdbc.update("UPDATE exercicios SET nome = 'Supino com barra' WHERE id = ?::uuid", supino);

        ver("/api/fichas/" + id).andExpect(jsonPath("$.divisoes[0].exercicios[0].nome").value("Supino reto"));
    }

    @Test
    void revisaoVencidaAparecaNaResposta() throws Exception {
        String id = montarFicha(aluno, divisoes(item(supino, 3, "10", null)));
        LocalDate hoje = Calendario.hoje(relogio.instant());

        jdbc.update("UPDATE fichas SET revisao_em = ? WHERE id = ?::uuid", hoje.plusDays(1), id);
        ver("/api/fichas/" + id).andExpect(jsonPath("$.revisaoVencida").value(false));
        jdbc.update("UPDATE fichas SET revisao_em = ? WHERE id = ?::uuid", hoje, id);
        ver("/api/fichas/" + id).andExpect(jsonPath("$.revisaoVencida").value(true));
    }

    @Test
    void tudoVaiParaATrilha() throws Exception {
        String primeira = montarFicha(aluno, divisoes(item(supino, 3, "10", "20 kg")));
        corrigir(primeira, "\"0\"", "{\"divisoes\": %s}".formatted(divisoes(item(supino, 3, "10", "25 kg"))))
                .andExpect(status().isOk());
        String segunda = montarFicha(aluno, divisoes(item(remada, 3, "10", null)));

        List<Map<String, Object>> trilha = jdbc.queryForList("""
                SELECT entidade_id::text AS ficha, acao, autor, alteracoes::text AS alteracoes FROM auditoria
                WHERE entidade = 'FICHA' ORDER BY momento, id""");
        assertThat(trilha).extracting(r -> r.get("ficha") + " " + r.get("acao")).containsExactly(
                primeira + " MONTADA", primeira + " CORRIGIDA", primeira + " ENCERRADA", segunda + " MONTADA");
        assertThat(trilha).allSatisfy(r -> assertThat(r.get("autor")).isEqualTo(instrutorId.toString()));
        String correcao = (String) trilha.get(1).get("alteracoes");
        assertThat(correcao).contains("\"divisoes\"").contains("20 kg").contains("25 kg");
        assertThat((String) trilha.get(2).get("alteracoes")).contains("\"encerradaEm\"").doesNotContain("\"divisoes\"");
    }
}
