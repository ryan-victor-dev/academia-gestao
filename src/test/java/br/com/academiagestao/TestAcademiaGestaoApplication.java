package br.com.academiagestao;

import org.springframework.boot.SpringApplication;

public class TestAcademiaGestaoApplication {

	public static void main(String[] args) {
		SpringApplication.from(AcademiaGestaoApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
