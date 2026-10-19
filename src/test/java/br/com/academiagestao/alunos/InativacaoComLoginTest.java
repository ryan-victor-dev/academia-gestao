package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
import br.com.academiagestao.auth.Perfil;
import br.com.academiagestao.auth.Usuario;
import br.com.academiagestao.auth.UsuarioRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Inativar o aluno desliga o login dele; reativar liga de novo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InativacaoComLoginTest {

    private static final String SENHA = "senha-da-ana-123";
    private static final RequestPostProcessor RECEPCAO =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));

    @Autowired
    MockMvc mvc;

    @Autowired
    AlunoRepository alunoRepository;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private Aluno ana;

    @BeforeEach
    void cadastrarAlunaComLogin() {
        jdbc.update("DELETE FROM usuarios");
        alunoRepository.deleteAll();
        ana = alunoRepository.save(Aluno.novo(new DadosAluno("Ana", "52998224725", "ana@email.com", null, null, null), Instant.now()));
        usuarioRepository.save(Usuario.deAluno("Ana", "ana@email.com", passwordEncoder.encode(SENHA), ana.getId()));
    }

    private ResultActions loginDaAna() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"ana@email.com\", \"senha\": \"%s\"}".formatted(SENHA)));
    }

    @Test
    void inativarOAlunoBloqueiaOLogin() throws Exception {
        loginDaAna().andExpect(status().isOk());

        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO)).andExpect(status().isOk());

        loginDaAna().andExpect(status().isUnauthorized());
        assertThat(usuarioRepository.findByAlunoId(ana.getId()).orElseThrow().isAtivo()).isFalse();
    }

    @Test
    void tokenQueOAlunoJaTinhaMorreNaHoraDaInativacao() throws Exception {
        String corpo = loginDaAna().andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(corpo, "$.token");
        mvc.perform(get("/api/alunos/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO));

        mvc.perform(get("/api/alunos/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reativarOAlunoLiberaOLoginDeNovo() throws Exception {
        mvc.perform(post("/api/alunos/" + ana.getId() + "/inativar").with(RECEPCAO));

        mvc.perform(post("/api/alunos/" + ana.getId() + "/reativar").with(RECEPCAO)).andExpect(status().isOk());

        loginDaAna().andExpect(status().isOk());
    }

    @Test
    void alunoSemLoginTambemPodeSerInativadoEReativado() throws Exception {
        Aluno semLogin = alunoRepository.save(Aluno.novo(new DadosAluno("Bia", "11144477735", null, null, null, null), Instant.now()));

        mvc.perform(post("/api/alunos/" + semLogin.getId() + "/inativar").with(RECEPCAO)).andExpect(status().isOk());
        mvc.perform(post("/api/alunos/" + semLogin.getId() + "/reativar").with(RECEPCAO)).andExpect(status().isOk());
    }

    @Test
    void inativarUmAlunoNaoMexeNoLoginDeOutros() throws Exception {
        usuarioRepository.save(Usuario.daEquipe("Rita", "rita@academia.com", "hash", Perfil.RECEPCAO));
        Aluno bia = alunoRepository.save(Aluno.novo(new DadosAluno("Bia", "11144477735", null, null, null, null), Instant.now()));

        mvc.perform(post("/api/alunos/" + bia.getId() + "/inativar").with(RECEPCAO));

        assertThat(usuarioRepository.findByAlunoId(ana.getId()).orElseThrow().isAtivo()).isTrue();
        assertThat(usuarioRepository.findByEmail("rita@academia.com").orElseThrow().isAtivo()).isTrue();
    }
}
