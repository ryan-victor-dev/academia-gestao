package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recuperação de ADMIN roda a aplicação sem servidor web (AcademiaGestaoApplication). Os
 * outros testes sobem sempre com web, e por isso não viram quando um bean que só existe com
 * web (o limite de requisições) passou a ser exigido no modo comando: o e2e pegou o comando
 * saindo com erro.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
class ModoComandoTest {

    @Autowired
    ApplicationContext contexto;

    @Test
    void sobeSemServidorWebComARecuperacaoDeAdmin() {
        assertThat(contexto.getBeanNamesForType(RecuperacaoDeAdmin.class)).hasSize(1);
        assertThat(contexto.getBeanNamesForType(SegurancaConfig.class)).isEmpty();
    }
}
