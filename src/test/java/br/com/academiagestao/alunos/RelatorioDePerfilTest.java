package br.com.academiagestao.alunos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.compartilhado.Calendario;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O relatório de idades e gênero (ADR 0048, decisão 4): só o ADMIN; as contagens por faixa, identidade e orientação de
 * cada grupo, com quem não respondeu à parte; os inativos só quando pedidos; e cada abertura no registro de acessos.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RelatorioDePerfilTest {

    private static final String ROTA = "/api/relatorios/idades-e-genero";

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock relogio;

    private LocalDate hoje;
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void preparar() {
        limpeza.apagarTudo();
        hoje = Calendario.hoje(relogio.instant());
    }

    private RequestPostProcessor perfil(String perfil, UUID id) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + perfil));
    }

    private UUID aluno(String nome, LocalDate nascimento, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf, data_nascimento, ativo) VALUES (?, ?, ?, ?, ?)", id, nome,
                CpfsParaTeste.aleatorio(), nascimento, ativo);
        return id;
    }

    private UUID daEquipe(String nome, String perfil, LocalDate nascimento, boolean ativo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, data_nascimento, ativo) VALUES (?, ?, ?, 'x', ?, ?, ?)",
                id, nome, id + "@teste.local", perfil, nascimento, ativo);
        return id;
    }

    private void identidade(String tabela, String coluna, UUID id, String genero, String generoOutra, String orientacao) {
        jdbc.update("INSERT INTO " + tabela + " (" + coluna + ", identidade_de_genero, identidade_de_genero_outra, orientacao_sexual,"
                + " atualizado_em) VALUES (?, ?, ?, ?, now())", id, genero, generoOutra, orientacao);
    }

    /** Alunos: 17 e 18 anos hoje (os limites da faixa), um sem data e um inativo; equipe: um com data, um sem, um desativado. */
    private void montar() {
        identidade("identidade_dos_alunos", "aluno_id", aluno("Ana", hoje.minusYears(17), true), "MULHER_CIS", null, "LESBICA");
        aluno("Bia", hoje.minusYears(18), true);
        identidade("identidade_dos_alunos", "aluno_id", aluno("Caio", null, true), "OUTRA", "bigênero", "PREFIRO_NAO_INFORMAR");
        aluno("Duda", hoje.minusYears(40), false);
        identidade("identidade_da_equipe", "usuario_id", daEquipe("Rita", "RECEPCAO", hoje.minusYears(30), true),
                "HOMEM_TRANS", null, "GAY");
        daEquipe("Ivo", "INSTRUTOR", null, true);
        daEquipe("Velho", "ADMIN", hoje.minusYears(60), false);
        // O login do aluno não é da equipe: a Bia aparece uma vez só, como aluna.
        UUID login = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, aluno_id) VALUES (?, 'Bia', ?, 'x', 'ALUNO',"
                + " (SELECT id FROM alunos WHERE nome = 'Bia'))", login, login + "@teste.local");
    }

    @Test
    void soOAdminAbre() throws Exception {
        for (String perfil : new String[] {"RECEPCAO", "INSTRUTOR", "ALUNO"}) {
            UUID quem = UUID.randomUUID();
            mvc.perform(get(ROTA).with(perfil(perfil, quem))).andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM acessos WHERE usuario_id = ?", Integer.class, quem)).isZero();
        }
    }

    @Test
    void contaCadaGrupoComQuemNaoRespondeuAParte() throws Exception {
        montar();

        mvc.perform(get(ROTA).with(perfil("ADMIN", admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.dia").value(hoje.toString()))
                .andExpect(jsonPath("$.comInativos").value(false))
                .andExpect(jsonPath("$.alunos.total").value(3))
                .andExpect(jsonPath("$.alunos.porFaixa.ATE_17").value(1))
                .andExpect(jsonPath("$.alunos.porFaixa.DE_18_A_29").value(1))
                .andExpect(jsonPath("$.alunos.porFaixa.DE_40_A_49").value(0))
                .andExpect(jsonPath("$.alunos.porFaixa.SEM_DATA").value(1))
                .andExpect(jsonPath("$.alunos.porIdentidadeDeGenero.MULHER_CIS").value(1))
                .andExpect(jsonPath("$.alunos.porIdentidadeDeGenero.OUTRA").value(1))
                .andExpect(jsonPath("$.alunos.porIdentidadeDeGenero.NAO_RESPONDIDO").value(1))
                .andExpect(jsonPath("$.alunos.porIdentidadeDeGenero.HOMEM_TRANS").value(0))
                .andExpect(jsonPath("$.alunos.porOrientacaoSexual.LESBICA").value(1))
                .andExpect(jsonPath("$.alunos.porOrientacaoSexual.PREFIRO_NAO_INFORMAR").value(1))
                .andExpect(jsonPath("$.alunos.porOrientacaoSexual.NAO_RESPONDIDO").value(1))
                .andExpect(jsonPath("$.equipe.total").value(2))
                .andExpect(jsonPath("$.equipe.porFaixa.DE_30_A_39").value(1))
                .andExpect(jsonPath("$.equipe.porFaixa.SEM_DATA").value(1))
                .andExpect(jsonPath("$.equipe.porFaixa.A_PARTIR_DE_60").value(0))
                .andExpect(jsonPath("$.equipe.porIdentidadeDeGenero.HOMEM_TRANS").value(1))
                .andExpect(jsonPath("$.equipe.porIdentidadeDeGenero.NAO_RESPONDIDO").value(1))
                .andExpect(jsonPath("$.equipe.porOrientacaoSexual.GAY").value(1))
                // Todas as opções aparecem, na ordem das telas, mesmo com zero.
                .andExpect(jsonPath("$.equipe.porFaixa.keys()").value(contains("ATE_17", "DE_18_A_29", "DE_30_A_39",
                        "DE_40_A_49", "DE_50_A_59", "A_PARTIR_DE_60", "SEM_DATA")))
                .andExpect(jsonPath("$.pessoas[*].nome").value(contains("Ana", "Bia", "Caio", "Ivo", "Rita")))
                .andExpect(jsonPath("$.pessoas[0].grupo").value("ALUNO"))
                .andExpect(jsonPath("$.pessoas[0].idade").value(17))
                .andExpect(jsonPath("$.pessoas[0].faixa").value("ATE_17"))
                .andExpect(jsonPath("$.pessoas[0].dataNascimento").value(hoje.minusYears(17).toString()))
                .andExpect(jsonPath("$.pessoas[1].idade").value(18))
                .andExpect(jsonPath("$.pessoas[2].idade").doesNotExist())
                .andExpect(jsonPath("$.pessoas[2].identidadeDeGeneroOutra").value("bigênero"))
                .andExpect(jsonPath("$.pessoas[4].grupo").value("EQUIPE"))
                .andExpect(jsonPath("$.pessoas[4].perfil").value("RECEPCAO"))
                .andExpect(jsonPath("$.pessoas[4].orientacaoSexual").value("GAY"));
    }

    @Test
    void osInativosSoQuandoPedidos() throws Exception {
        montar();

        mvc.perform(get(ROTA).param("inativos", "true").with(perfil("ADMIN", admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comInativos").value(true))
                .andExpect(jsonPath("$.alunos.total").value(4))
                .andExpect(jsonPath("$.alunos.porFaixa.DE_40_A_49").value(1))
                .andExpect(jsonPath("$.equipe.total").value(3))
                .andExpect(jsonPath("$.equipe.porFaixa.A_PARTIR_DE_60").value(1))
                .andExpect(jsonPath("$.pessoas[?(@.nome == 'Duda')].ativo").value(contains(false)));
    }

    @Test
    void cadaAberturaVaiParaORegistroDeAcessosNumaLinhaSo() throws Exception {
        montar();

        mvc.perform(get(ROTA).with(perfil("ADMIN", admin))).andExpect(status().isOk());
        mvc.perform(get(ROTA).param("inativos", "true").with(perfil("ADMIN", admin))).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT tipo || '|' || coalesce(conta, '-') || '|' || coalesce(aluno_id::text, '-') FROM acessos"
                + " WHERE usuario_id = ?", String.class, admin)).containsExactly("RELATORIO_PERFIL|-|-", "RELATORIO_PERFIL|-|-");
    }

    @Test
    void oBancoExigeQuemENaoAceitaContaNemAlunoNoRelatorio() {
        assertThat(tentar("INSERT INTO acessos (tipo, ip) VALUES ('RELATORIO_PERFIL', '127.0.0.1')")).contains("ck_acessos_quem");
        assertThat(tentar("INSERT INTO acessos (tipo, usuario_id, conta, ip) VALUES ('RELATORIO_PERFIL', gen_random_uuid(), 'x', '127.0.0.1')"))
                .contains("ck_acessos_conta");
        assertThat(tentar("INSERT INTO acessos (tipo, usuario_id, aluno_id, ip) VALUES ('RELATORIO_PERFIL', gen_random_uuid(),"
                + " gen_random_uuid(), '127.0.0.1')")).contains("ck_acessos_consulta");
        assertThat(tentar("INSERT INTO acessos (tipo, usuario_id, ip) VALUES ('RELATORIO_PERFIL', gen_random_uuid(), '127.0.0.1')"))
                .isEqualTo("aceitou");
        // A consulta a um aluno continua como era.
        assertThat(tentar("INSERT INTO acessos (tipo, usuario_id, ip) VALUES ('CONSULTA_ALUNO', gen_random_uuid(), '127.0.0.1')"))
                .contains("ck_acessos");
    }

    private String tentar(String sql) {
        try {
            jdbc.update(sql);
            return "aceitou";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
