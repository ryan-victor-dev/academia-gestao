package br.com.academiagestao.alunos;

import br.com.academiagestao.TestcontainersConfiguration;
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

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MeuCadastroTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AlunoRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private Aluno ana;

    @BeforeEach
    void cadastrar() {
        jdbc.update("DELETE FROM usuarios WHERE aluno_id IS NOT NULL");
        repository.deleteAll();
        ana = repository.save(Aluno.novo(new DadosAluno("Ana", "52998224725", null, null, null, null)));
        repository.save(Aluno.novo(new DadosAluno("Bia", "11144477735", null, null, null, null)));
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

    @Test
    void alunoVeOProprioCadastro() throws Exception {
        mvc.perform(get("/api/alunos/me").with(tokenDeAluno(ana.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ana.getId().toString()))
                .andExpect(jsonPath("$.nome").value("Ana"));
    }

    @Test
    void alunoInativoContinuaVendoOsPropriosDados() throws Exception {
        ana.inativar();
        repository.save(ana);

        mvc.perform(get("/api/alunos/me").with(tokenDeAluno(ana.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    @Test
    void equipeNaoUsaORotaDoAluno() throws Exception {
        mvc.perform(get("/api/alunos/me").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenDeAlunoSemAlunoIdDevolve403() throws Exception {
        mvc.perform(get("/api/alunos/me").with(tokenDeAluno(null)))
                .andExpect(status().isForbidden());
    }
}
