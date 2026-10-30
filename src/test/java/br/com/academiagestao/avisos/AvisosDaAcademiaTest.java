package br.com.academiagestao.avisos;

import br.com.academiagestao.CorreioDeTeste;
import br.com.academiagestao.LimpezaDoBanco;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Os avisos da academia (ADR 0047, decisão 8): o evento ou comunicado publicado com "avisar" vai por e-mail e SMS aos
 * alunos ativos e por e-mail à equipe; quem desligou não recebe; excluir cancela o que falta.
 */
@Import({TestcontainersConfiguration.class, CorreioDeTeste.Config.class})
@SpringBootTest(properties = {"academia.avisos.primeira-vez=PT1H", "academia.sms.provedor=simulado"})
@AutoConfigureMockMvc
class AvisosDaAcademiaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AvisosDaAcademia avisos;

    @Autowired
    CorreioDeTeste correio;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LimpezaDoBanco limpeza;

    private final List<UUID> alunos = new ArrayList<>();
    private final List<UUID> usuarios = new ArrayList<>();
    private final List<String> celulares = new ArrayList<>();
    private UUID recepcao;

    @BeforeEach
    void preparar() {
        correio.falhar(false);
        recepcao = usuario("RECEPCAO", "aviso-recepcao-" + UUID.randomUUID() + "@teste.local");
        // A fila conta com quem está no banco agora: os avisos pendentes de outros testes não atrapalham as contas.
        jdbc.update("UPDATE envios_da_academia SET situacao = 'CANCELADO' WHERE situacao IN ('PENDENTE', 'FALHOU')");
    }

    @AfterEach
    void limpar() {
        correio.falhar(false);
        jdbc.update("DELETE FROM envios_da_academia WHERE usuario_id = ANY (?) OR aluno_id = ANY (?)",
                usuarios.toArray(UUID[]::new), alunos.toArray(UUID[]::new));
        jdbc.update("DELETE FROM auditoria WHERE entidade = 'PUBLICACAO' AND autor = ?", recepcao.toString());
        jdbc.update("DELETE FROM publicacoes WHERE criada_por = ?", recepcao);
        jdbc.update("DELETE FROM sms_simulados WHERE celular = ANY (?)", (Object) celulares.toArray(String[]::new));
        for (UUID aluno : alunos) {
            limpeza.apagar("alunos", aluno);
        }
        for (UUID usuario : usuarios) {
            limpeza.apagar("usuarios", usuario);
        }
    }

    private UUID usuario(String perfil, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil) VALUES (?, 'Rita Alves', ?, 'x', ?)", id, email, perfil);
        usuarios.add(id);
        return id;
    }

    private UUID aluno(String email, String celular, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, email, telefone, ativo) VALUES (?, 'Duda Lima', ?, ?, ?, ?)", id,
                CpfsParaTeste.aleatorio(), email, celular, ativo);
        alunos.add(id);
        if (celular != null) {
            celulares.add(celular);
        }
        return id;
    }

    private String celular() {
        return "219" + String.format("%08d", (long) (Math.random() * 100_000_000));
    }

    private static RequestPostProcessor como(String perfil, UUID usuario) {
        return jwt().jwt(j -> j.subject(usuario.toString()).claim("perfil", perfil))
                .authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private static RequestPostProcessor oAluno(UUID aluno, String... extras) {
        List<org.springframework.security.core.GrantedAuthority> autoridades = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_ALUNO")));
        for (String e : extras) {
            autoridades.add(new SimpleGrantedAuthority(e));
        }
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("alunoId", aluno.toString())).authorities(autoridades);
    }

    private ResultActions publicar(String tipo, boolean avisar) throws Exception {
        String evento = tipo.equals("EVENTO") ? ", \"eventoInicio\": \"2030-11-09T12:00:00Z\", \"eventoLocal\": \"Sala 2\"" : "";
        return mvc.perform(post("/api/publicacoes").with(como("RECEPCAO", recepcao)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\": \"%s\", \"titulo\": \"Aulão\", \"texto\": \"Venha!\"%s, \"avisar\": %s}".formatted(tipo, evento, avisar)));
    }

    private String publicado(String tipo) throws Exception {
        return JsonPath.read(publicar(tipo, true).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions desligar(RequestPostProcessor quem, boolean recebe) throws Exception {
        return mvc.perform(put("/api/avisos/preferencias").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recebeAvisosDaAcademia\": " + recebe + "}"));
    }

    private List<String> sms(String celular) {
        return jdbc.queryForList("SELECT texto FROM sms_simulados WHERE celular = ?", String.class, celular);
    }

    @Test
    void eventoComAvisoVaiParaAlunosEEquipeMenosQuemDesligouOuSaiu() throws Exception {
        String celular = celular();
        String emailAluno = "aviso-aluno-" + UUID.randomUUID() + "@teste.local";
        aluno(emailAluno, celular, true);
        String emailInativo = "aviso-inativo-" + UUID.randomUUID() + "@teste.local";
        aluno(emailInativo, celular(), false);
        String emailDesligado = "aviso-desligado-" + UUID.randomUUID() + "@teste.local";
        UUID desligado = aluno(emailDesligado, null, true);
        desligar(oAluno(desligado), false).andExpect(status().isOk());
        String emailInstrutor = "aviso-instrutor-" + UUID.randomUUID() + "@teste.local";
        usuario("INSTRUTOR", emailInstrutor);
        UUID comFixo = aluno(null, "1133334444", true);

        String publicacao = publicado("EVENTO");
        // Quem desligou e o telefone fixo nem entram na fila.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM envios_da_academia WHERE publicacao_id = ?::uuid AND aluno_id IN (?, ?)",
                Integer.class, publicacao, desligado, comFixo)).isZero();
        avisos.enviarPendentes();
        avisos.enviarPendentes();

        assertThat(correio.para(emailAluno)).singleElement().satisfies(m -> {
            assertThat(m.assunto()).isEqualTo("Evento da academia: Aulão");
            assertThat(m.texto()).contains("09/11/2030 às 09:00 · Sala 2").contains("Venha!").contains("desligue em Meus dados");
        });
        assertThat(sms(celular)).containsExactly("Academia: evento: Aulão - 09/11 09:00, Sala 2. Veja no app.");
        assertThat(correio.para(emailInstrutor)).hasSize(1);
        assertThat(correio.para(emailInativo)).isEmpty();
        assertThat(correio.para(emailDesligado)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM envios_da_academia WHERE publicacao_id = ?::uuid AND situacao = 'ENVIADO' "
                + "AND (aluno_id = ANY (?) OR usuario_id = ANY (?))", Integer.class, publicacao, alunos.toArray(UUID[]::new),
                usuarios.toArray(UUID[]::new))).as("aluno: e-mail e SMS; instrutor e recepção: e-mail").isEqualTo(4);
    }

    @Test
    void comunicadoSemAvisoENoticiaComAviso() throws Exception {
        String emailAluno = "aviso-aluno-" + UUID.randomUUID() + "@teste.local";
        aluno(emailAluno, null, true);

        String semAviso = JsonPath.read(publicar("COMUNICADO", false).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        publicar("NOTICIA", true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("avisar: só eventos e comunicados são avisados por e-mail e SMS"));
        avisos.enviarPendentes();

        assertThat(correio.para(emailAluno)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM avisos_da_academia WHERE publicacao_id = ?::uuid", Integer.class, semAviso)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM publicacoes WHERE tipo = 'NOTICIA' AND criada_por = ?", Integer.class, recepcao)).isZero();
    }

    @Test
    void quemDesligaDepoisDeEntrarNaFilaNaoRecebeEExcluirCancela() throws Exception {
        String email = "aviso-aluno-" + UUID.randomUUID() + "@teste.local";
        UUID aluno = aluno(email, null, true);
        String outroEmail = "aviso-aluno-" + UUID.randomUUID() + "@teste.local";
        aluno(outroEmail, null, true);

        publicado("COMUNICADO");
        desligar(oAluno(aluno), false).andExpect(status().isOk());
        String excluida = publicado("EVENTO");
        mvc.perform(delete("/api/publicacoes/" + excluida).with(como("RECEPCAO", recepcao))).andExpect(status().isNoContent());
        avisos.enviarPendentes();

        assertThat(correio.para(email)).isEmpty();
        assertThat(correio.para(outroEmail)).extracting(m -> m.assunto()).containsExactly("Comunicado da academia: Aulão");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM envios_da_academia WHERE publicacao_id = ?::uuid AND situacao <> 'CANCELADO'",
                Integer.class, excluida)).isZero();
    }

    @Test
    void servidorDeEmailForaDoArTentaAteTresVezes() throws Exception {
        String email = "aviso-aluno-" + UUID.randomUUID() + "@teste.local";
        UUID aluno = aluno(email, null, true);
        correio.falhar(true);
        publicado("COMUNICADO");

        avisos.enviarPendentes();
        avisos.enviarPendentes();
        correio.falhar(false);
        avisos.enviarPendentes();
        assertThat(correio.para(email)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT situacao || '/' || tentativas FROM envios_da_academia WHERE aluno_id = ?", String.class, aluno))
                .isEqualTo("ENVIADO/3");

        String outro = "aviso-aluno-" + UUID.randomUUID() + "@teste.local";
        UUID outroAluno = aluno(outro, null, true);
        correio.falhar(true);
        publicado("COMUNICADO");
        for (int i = 0; i < 4; i++) {
            avisos.enviarPendentes();
        }
        correio.falhar(false);
        avisos.enviarPendentes();
        assertThat(correio.para(outro)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT situacao || '/' || tentativas FROM envios_da_academia WHERE aluno_id = ?", String.class, outroAluno))
                .isEqualTo("FALHOU/3");
    }

    @Test
    void cadaUmLigaEDesligaOsProprios() throws Exception {
        UUID aluno = aluno(null, null, true);
        mvc.perform(get("/api/avisos/preferencias").with(oAluno(aluno))).andExpect(jsonPath("$.recebeAvisosDaAcademia").value(true));
        desligar(oAluno(aluno), false).andExpect(jsonPath("$.recebeAvisosDaAcademia").value(false));
        mvc.perform(get("/api/avisos/preferencias").with(oAluno(aluno))).andExpect(jsonPath("$.recebeAvisosDaAcademia").value(false));
        desligar(oAluno(aluno), true).andExpect(jsonPath("$.recebeAvisosDaAcademia").value(true));
        assertThat(jdbc.queryForObject("SELECT recebe_da_academia FROM preferencias_de_avisos WHERE aluno_id = ?", Boolean.class, aluno)).isTrue();

        // A da equipe é pelo login, e não mexe na do aluno.
        desligar(como("RECEPCAO", recepcao), false).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT recebe_da_academia FROM preferencias_de_avisos WHERE usuario_id = ?", Boolean.class, recepcao)).isFalse();
        mvc.perform(get("/api/avisos/preferencias").with(oAluno(aluno))).andExpect(jsonPath("$.recebeAvisosDaAcademia").value(true));

        desligar(oAluno(aluno, "SOMENTE_LEITURA"), false).andExpect(status().isForbidden());
        mvc.perform(put("/api/avisos/preferencias").with(oAluno(aluno)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/avisos/preferencias")).andExpect(status().isUnauthorized());
    }

    /** Quem deixou de ser ativo depois de entrar na fila não recebe. */
    @Test
    void alunoInativadoDepoisDeEntrarNaFilaNaoRecebe() throws Exception {
        String email = "aviso-aluno-" + UUID.randomUUID() + "@teste.local";
        UUID aluno = aluno(email, null, true);
        publicado("COMUNICADO");
        jdbc.update("UPDATE alunos SET ativo = false WHERE id = ?", aluno);

        avisos.enviarPendentes();

        assertThat(correio.para(email)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT situacao FROM envios_da_academia WHERE aluno_id = ?", String.class, aluno)).isEqualTo("CANCELADO");
    }
}
