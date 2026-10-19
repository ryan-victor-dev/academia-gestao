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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LoginDoAlunoTest {

    private static final RequestPostProcessor RECEPCAO =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_RECEPCAO"));

    @Autowired
    MockMvc mvc;

    @Autowired
    AlunoRepository alunoRepository;

    @Autowired
    UsuarioRepository usuarioRepository;

    @Autowired
    JdbcTemplate jdbc;

    private Aluno ana;

    @BeforeEach
    void cadastrar() {
        jdbc.update("DELETE FROM usuarios");
        alunoRepository.deleteAll();
        ana = alunoRepository.save(Aluno.novo(new DadosAluno("Ana", "52998224725", "Ana@Email.com", null, null, null), Instant.now()));
    }

    private ResultActions criarLogin(UUID alunoId) throws Exception {
        return mvc.perform(post("/api/alunos/" + alunoId + "/login").with(RECEPCAO));
    }

    private ResultActions login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, senha)));
    }

    private static String campo(ResultActions resposta, String caminho) throws Exception {
        return JsonPath.read(resposta.andReturn().getResponse().getContentAsString(), caminho);
    }

    @Test
    void criaLoginComSenhaTemporariaQueNaoPodeSerGuardadaEmCache() throws Exception {
        criarLogin(ana.getId())
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("ana@email.com"))
                .andExpect(jsonPath("$.senhaTemporaria").isString());

        Usuario usuario = usuarioRepository.findByEmail("ana@email.com").orElseThrow();
        assertThat(usuario.getPerfil()).isEqualTo(Perfil.ALUNO);
        assertThat(usuario.getAlunoId()).isEqualTo(ana.getId());
        assertThat(usuario.isPrecisaTrocarSenha()).isTrue();
    }

    @Test
    void fluxoCompletoAteOAlunoVerOProprioCadastro() throws Exception {
        String senhaTemporaria = campo(criarLogin(ana.getId()), "$.senhaTemporaria");

        // 1. Entra com a senha temporária: só pode trocar a senha.
        ResultActions primeiroLogin = login("ana@email.com", senhaTemporaria)
                .andExpect(jsonPath("$.precisaTrocarSenha").value(true));
        String tokenTemporario = campo(primeiroLogin, "$.token");
        mvc.perform(get("/api/alunos/me").header("Authorization", "Bearer " + tokenTemporario))
                .andExpect(status().isForbidden());

        // 2. Troca a senha e recebe um token normal.
        String tokenNovo = campo(mvc.perform(post("/api/auth/trocar-senha")
                        .header("Authorization", "Bearer " + tokenTemporario)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"senhaAtual\": \"%s\", \"novaSenha\": \"senha-da-ana-123\"}"
                                .formatted(senhaTemporaria)))
                .andExpect(status().isOk()), "$.token");

        // 3. Agora vê o próprio cadastro.
        mvc.perform(get("/api/alunos/me").header("Authorization", "Bearer " + tokenNovo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ana.getId().toString()));
    }

    @Test
    void segundoLoginParaOMesmoAlunoDevolve409() throws Exception {
        criarLogin(ana.getId()).andExpect(status().isCreated());

        criarLogin(ana.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Este aluno já tem login"));
    }

    @Test
    void emailJaUsadoPorOutroLoginDevolve409() throws Exception {
        usuarioRepository.save(Usuario.daEquipe("Outra Ana", "ana@email.com", "hash", Perfil.RECEPCAO));

        criarLogin(ana.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Este e-mail já é usado por outro login"));
    }

    @Test
    void alunoSemEmailDevolve400() throws Exception {
        Aluno semEmail = alunoRepository.save(Aluno.novo(
                new DadosAluno("Bia", "11144477735", null, null, null, null), Instant.now()));

        criarLogin(semEmail.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("cadastre um e-mail no aluno antes de criar o login"));
    }

    @Test
    void alunoInativoDevolve409() throws Exception {
        ana.inativar();
        alunoRepository.save(ana);

        criarLogin(ana.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Aluno inativo"));
        assertThat(usuarioRepository.existsByAlunoId(ana.getId())).isFalse();
    }

    @Test
    void alunoInexistenteDevolve404() throws Exception {
        criarLogin(UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
