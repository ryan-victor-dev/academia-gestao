package br.com.academiagestao.compartilhado;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O fuso da academia está em dois lugares: no Java ({@link Calendario#FUSO}) e no banco (o
 * gatilho que só aceita lançamento no caixa do dia, V35). Se um mudar sem o outro, o caixa do
 * dia passa a recusar o pagamento perto da meia-noite (pendências baixas, item 17).
 *
 * Lê do banco a definição atual de todas as funções e visões (e não as migrações: uma migração
 * nova pode redefinir a função, e as de correção de dados já rodaram e não valem mais).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FusoNoBancoTest {

    private static final Pattern FUSO_NO_SQL = Pattern.compile("(?i)AT\\s+TIME\\s+ZONE\\s+'([^']*)'");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void oBancoUsaOMesmoFusoDoCalendario() {
        List<String> definicoes = new ArrayList<>(jdbc.queryForList("""
                SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public'""", String.class));
        definicoes.addAll(jdbc.queryForList("SELECT definition FROM pg_views WHERE schemaname = 'public'", String.class));

        List<String> fusos = fusos(definicoes);

        // Sem nenhum, o teste passaria no vazio (uma consulta errada, o gatilho escrito de outro jeito).
        assertThat(fusos).isNotEmpty().containsOnly(Calendario.FUSO.getId());
    }

    @Test
    void achaOFusoEscritoDeQualquerJeito() {
        assertThat(fusos(List.of("x AT TIME ZONE 'America/Sao_Paulo'", "y at  time\n zone 'UTC'::date")))
                .containsExactly("America/Sao_Paulo", "UTC");
    }

    private static List<String> fusos(List<String> definicoes) {
        List<String> fusos = new ArrayList<>();
        for (String definicao : definicoes) {
            Matcher achado = FUSO_NO_SQL.matcher(definicao);
            while (achado.find()) {
                fusos.add(achado.group(1));
            }
        }
        return fusos;
    }
}
