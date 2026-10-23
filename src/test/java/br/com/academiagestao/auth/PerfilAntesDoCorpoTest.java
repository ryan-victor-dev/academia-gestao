package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Quem não tem o perfil recebe 403 antes de o corpo ser lido: sem isso, a validação do corpo
 * (que vem antes do @PreAuthorize) respondia 400 com as regras dos campos a quem nem podia usar
 * a rota (diagnóstico de 2026-10-18, S2; etapa 6, S5). Percorre todas as rotas com @PreAuthorize
 * e corpo, inclusive as que forem criadas depois.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PerfilAntesDoCorpoTest {

    private static final List<String> PERFIS = List.of("ADMIN", "RECEPCAO", "INSTRUTOR", "ALUNO");
    private static final Pattern PERFIL = Pattern.compile("'([A-Z]+)'");
    private static final Pattern VARIAVEL = Pattern.compile("\\{([^}]+)}");

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping rotas;

    @Test
    void perfilSemPermissaoRecebe403MesmoComCorpoInvalido() throws Exception {
        List<String> conferidas = new ArrayList<>();
        List<String> erradas = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> rota : rotas.getHandlerMethods().entrySet()) {
            HandlerMethod metodo = rota.getValue();
            PreAuthorize regra = metodo.getMethodAnnotation(PreAuthorize.class);
            boolean temCorpo = Arrays.stream(metodo.getMethodParameters())
                    .anyMatch(p -> p.hasParameterAnnotation(RequestBody.class));
            if (regra == null || !temCorpo) {
                continue;
            }
            List<String> permitidos = new ArrayList<>();
            Matcher perfil = PERFIL.matcher(regra.value());
            while (perfil.find()) {
                permitidos.add(perfil.group(1));
            }
            String semPermissao = PERFIS.stream().filter(p -> !permitidos.contains(p)).findFirst().orElse(null);
            if (semPermissao == null) {
                continue;
            }
            String caminho = caminho(rota.getKey(), metodo);
            String verbo = rota.getKey().getMethodsCondition().getMethods().iterator().next().name();
            int status = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(verbo), caminho)
                            .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                    .authorities(new SimpleGrantedAuthority("ROLE_" + semPermissao)))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
            conferidas.add(verbo + " " + caminho);
            if (status != 403) {
                erradas.add(verbo + " " + caminho + " como " + semPermissao + ": " + status);
            }
        }
        assertThat(conferidas).as("rotas com @PreAuthorize e corpo").hasSizeGreaterThan(15);
        assertThat(erradas).isEmpty();
    }

    /** O caminho com as variáveis preenchidas: uma data onde a variável é data, um id nas outras. */
    private static String caminho(RequestMappingInfo info, HandlerMethod metodo) {
        String padrao = info.getPathPatternsCondition().getPatternValues().iterator().next();
        Matcher variavel = VARIAVEL.matcher(padrao);
        StringBuilder caminho = new StringBuilder();
        while (variavel.find()) {
            String nome = variavel.group(1);
            boolean ehData = Arrays.stream(metodo.getMethodParameters()).anyMatch(p -> ehVariavel(p, nome)
                    && p.getParameterType().equals(LocalDate.class));
            variavel.appendReplacement(caminho, ehData ? "2030-01-01" : UUID.randomUUID().toString());
        }
        variavel.appendTail(caminho);
        return caminho.toString();
    }

    private static boolean ehVariavel(MethodParameter parametro, String nome) {
        PathVariable anotacao = parametro.getParameterAnnotation(PathVariable.class);
        return anotacao != null && (nome.equals(anotacao.value()) || nome.equals(anotacao.name())
                || nome.equals(parametro.getParameterName()));
    }
}
