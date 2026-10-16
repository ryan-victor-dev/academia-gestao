package br.com.academiagestao;

import br.com.academiagestao.auth.RecuperacaoDeAdmin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AcademiaGestaoApplication {

	public static void main(String[] args) {
		SpringApplication aplicacao = new SpringApplication(AcademiaGestaoApplication.class);
		if (RecuperacaoDeAdmin.foiPedida(args)) {
			// Modo comando: sem servidor web; faz a recuperação e encerra com o código de saída.
			aplicacao.setWebApplicationType(WebApplicationType.NONE);
			System.exit(SpringApplication.exit(aplicacao.run(args)));
		}
		aplicacao.run(args);
	}

}
