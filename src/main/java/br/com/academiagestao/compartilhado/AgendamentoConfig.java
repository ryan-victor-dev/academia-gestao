package br.com.academiagestao.compartilhado;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Liga as tarefas periódicas (@Scheduled), como a limpeza das tentativas de login antigas. */
@Configuration
@EnableScheduling
class AgendamentoConfig {
}
