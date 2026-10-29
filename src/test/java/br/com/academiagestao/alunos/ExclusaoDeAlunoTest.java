package br.com.academiagestao.alunos;

import br.com.academiagestao.LimpezaDoBanco;
import br.com.academiagestao.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excluir o aluno (ADR 0043): só o que nunca foi usado sai, com o login dele, se nunca entrou.
 * Com histórico (matrícula, ficha, pagamento, primeiro acesso, login já usado), 409: inative.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExclusaoDeAlunoTest {

    private static final RequestPostProcessor ADMIN = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @Autowired
    LimpezaDoBanco limpeza;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> criados = new ArrayList<>();

    @AfterEach
    void limpar() {
        for (UUID id : criados) {
            jdbc.update("DELETE FROM acessos WHERE usuario_id IN (SELECT id FROM usuarios WHERE aluno_id = ?)", id);
            jdbc.update("DELETE FROM auditoria WHERE entidade_id = ? OR entidade_id IN (SELECT id FROM usuarios WHERE aluno_id = ?)"
                    + " OR autor IN (SELECT id::text FROM usuarios WHERE aluno_id = ?)", id, id, id);
            limpeza.apagar("alunos", id);
        }
        criados.clear();
    }

    private UUID aluno() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alunos (id, nome, cpf) VALUES (?, 'Aluno', ?)", id, CpfsParaTeste.aleatorio());
        criados.add(id);
        return id;
    }

    private UUID login(UUID aluno) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO usuarios (id, nome, email, senha_hash, perfil, aluno_id) VALUES (?, 'Aluno', ?, 'x', 'ALUNO', ?)",
                id, id + "@teste.local", aluno);
        return id;
    }

    private void acesso(String tipo, UUID usuario) {
        jdbc.update("INSERT INTO acessos (tipo, usuario_id, conta, ip) VALUES (?, ?, 'aluno@teste.local', '127.0.0.1')",
                tipo, usuario);
    }

    private ResultActions excluir(UUID aluno) throws Exception {
        return mvc.perform(delete("/api/alunos/" + aluno).with(ADMIN));
    }

    private int existe(String tabela, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabela + " WHERE id = ?", Integer.class, id);
    }

    private List<String> trilha(UUID id) {
        return jdbc.queryForList("SELECT acao FROM auditoria WHERE entidade_id = ? ORDER BY id", String.class, id);
    }

    @Test
    void alunoNuncaUsadoEhExcluidoEATrilhaGuardaOUltimoRetrato() throws Exception {
        UUID aluno = aluno();
        String cpf = jdbc.queryForObject("SELECT cpf FROM alunos WHERE id = ?", String.class, aluno);

        excluir(aluno).andExpect(status().isNoContent());

        assertThat(existe("alunos", aluno)).isZero();
        assertThat(trilha(aluno)).containsExactly("EXCLUIDO");
        String excluido = jdbc.queryForObject("SELECT alteracoes::text FROM auditoria WHERE entidade = 'ALUNO'"
                + " AND entidade_id = ?", String.class, aluno);
        assertThat(JsonPath.<String>read(excluido, "$.cpf.antes")).isEqualTo(cpf);
        assertThat(JsonPath.<Object>read(excluido, "$.cpf.depois")).isNull();
    }

    @Test
    void oLoginNuncaUsadoSaiJunto() throws Exception {
        UUID aluno = aluno();
        UUID login = login(aluno);
        jdbc.update("INSERT INTO sessoes (id, usuario_id, segredo_hash, versao_token, criada_em, renovada_em, expira_em)"
                + " VALUES (gen_random_uuid(), ?, decode(repeat('00', 32), 'hex'), 0, now(), now(), now() + interval '1 day')",
                login);
        // Uma senha errada não é uso: qualquer um que tentasse adivinhar impediria a exclusão.
        acesso("LOGIN_FALHO", login);

        excluir(aluno).andExpect(status().isNoContent());

        assertThat(existe("alunos", aluno)).isZero();
        assertThat(existe("usuarios", login)).isZero();
        assertThat(trilha(login)).containsExactly("EXCLUIDO");
        String excluido = jdbc.queryForObject("SELECT alteracoes::text FROM auditoria WHERE entidade_id = ?", String.class, login);
        assertThat(JsonPath.<String>read(excluido, "$.email.antes")).isEqualTo(login + "@teste.local");
        jdbc.update("DELETE FROM acessos WHERE usuario_id = ?", login);
        jdbc.update("DELETE FROM auditoria WHERE entidade_id = ?", login);
    }

    @Test
    void oAlunoQueJaEntrouNoAppNaoEhExcluido() throws Exception {
        UUID aluno = aluno();
        UUID login = login(aluno);
        acesso("LOGIN", login);

        excluir(aluno)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Aluno com histórico"))
                .andExpect(jsonPath("$.detail").value(containsString("entrou no app")))
                .andExpect(jsonPath("$.detail").value(containsString("inative")));
        assertThat(existe("alunos", aluno)).isOne();
        assertThat(existe("usuarios", login)).isOne();
        assertThat(trilha(aluno)).isEmpty();
    }

    /** O login que fez algo (um Pix gerado pelo app, por exemplo) é autor na trilha. */
    @Test
    void oLoginAutorNaTrilhaTambemConta() throws Exception {
        UUID aluno = aluno();
        UUID login = login(aluno);
        jdbc.update("INSERT INTO auditoria (autor, entidade, entidade_id, acao, alteracoes) VALUES (?, 'PLANO', ?, 'TESTE', '{}')",
                login.toString(), UUID.randomUUID());

        excluir(aluno).andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Aluno com histórico"));
        assertThat(existe("usuarios", login)).isOne();
    }

    /**
     * O que aponta para o login (aqui, o código de confirmação do e-mail) também é uso dele: a
     * recusa é a do login, e não a do cadastro.
     */
    @Test
    void oLoginComCodigoDeEmailTambemConta() throws Exception {
        UUID aluno = aluno();
        UUID login = login(aluno);
        jdbc.update("""
                INSERT INTO codigos_de_email (id, usuario_id, finalidade, email, codigo_hash, criado_em, expira_em, encerrado_em)
                VALUES (gen_random_uuid(), ?, 'CONFIRMACAO', 'aluno@teste.local', 'x', now(), now() + interval '15 minutes', now())""",
                login);

        excluir(aluno)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("entrou no app")));
        assertThat(existe("usuarios", login)).isOne();
        jdbc.update("DELETE FROM codigos_de_email WHERE usuario_id = ?", login);
    }

    @Test
    void comMatriculaNaoEhExcluido() throws Exception {
        UUID aluno = aluno();
        UUID plano = UUID.randomUUID();
        jdbc.update("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (?, ?, 100, 1)", plano, "Plano " + plano);
        LocalDate inicio = LocalDate.now().minusMonths(3);
        // Encerrada há tempo: o histórico fica mesmo sem matrícula valendo.
        jdbc.update("""
                INSERT INTO matriculas (id, aluno_id, plano_id, plano_nome, valor_mensal, duracao_meses, inicio, fim, criada_em)
                VALUES (?, ?, ?, 'Mensal', 100, 1, ?, ?, now())""",
                UUID.randomUUID(), aluno, plano, Date.valueOf(inicio), Date.valueOf(inicio.plusMonths(1).minusDays(1)));

        excluir(aluno)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Aluno com histórico"))
                .andExpect(jsonPath("$.detail").value(containsString("matrícula")));
        assertThat(existe("alunos", aluno)).isOne();
        assertThat(trilha(aluno)).isEmpty();
        limpeza.apagar("alunos", aluno);
        limpeza.apagar("planos", plano);
    }

    /** O login nunca usado não sai sozinho quando o aluno fica: tudo na mesma transação. */
    @Test
    void comPedidoDePrimeiroAcessoNaoEhExcluidoNemOLogin() throws Exception {
        UUID aluno = aluno();
        UUID login = login(aluno);
        jdbc.update("""
                INSERT INTO codigos_de_email (id, aluno_id, finalidade, email, codigo_hash, criado_em, expira_em, encerrado_em)
                VALUES (gen_random_uuid(), ?, 'PRIMEIRO_ACESSO', 'aluno@teste.local', 'x', now(), now() + interval '15 minutes', now())""", aluno);

        excluir(aluno).andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Aluno com histórico"));
        assertThat(existe("alunos", aluno)).isOne();
        assertThat(existe("usuarios", login)).isOne();
        assertThat(trilha(login)).isEmpty();
    }

    @Test
    void alunoInexistenteDa404() throws Exception {
        excluir(UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
